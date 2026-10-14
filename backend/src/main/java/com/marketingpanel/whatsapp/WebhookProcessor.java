package com.marketingpanel.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.campaign.CampaignStats;
import com.marketingpanel.common.*;
import com.marketingpanel.crm.ContactService;
import com.marketingpanel.inbox.InboxService;
import com.marketingpanel.jobs.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** Processes stored Meta webhooks in a job so the HTTP endpoint can answer within 200 ms. */
@Component
public class WebhookProcessor implements JobHandler {
    private static final Logger log = LoggerFactory.getLogger(WebhookProcessor.class);
    private static final List<String> ORDER = List.of("QUEUED", "SENT", "DELIVERED", "READ");
    private static final Set<String> STOP_WORDS = Set.of("stop", "unsubscribe", "நிறுத்து", "stop all", "opt out", "optout");

    private final Db db;
    private final InboxService inbox;
    private final ContactService contacts;
    private final TemplateService templates;
    private final RateService rates;
    private final WalletService wallet;
    private final CampaignStats stats;
    private final NotificationService notifications;
    private final PlanService plans;
    private final ApplicationEventPublisher events;

    public WebhookProcessor(Db db, InboxService inbox, ContactService contacts, TemplateService templates, RateService rates, WalletService wallet,
                            CampaignStats stats, NotificationService notifications, PlanService plans, ApplicationEventPublisher events) {
        this.db = db;
        this.inbox = inbox;
        this.contacts = contacts;
        this.templates = templates;
        this.rates = rates;
        this.wallet = wallet;
        this.stats = stats;
        this.notifications = notifications;
        this.plans = plans;
        this.events = events;
    }

    @Override
    public String type() {
        return "WEBHOOK_PROCESS";
    }

    @Override
    public void handle(JsonNode p) {
        long id = p.get("id").asLong();
        Row ev = db.row("select * from webhook_events where id=:id", "id", id).orElse(null);
        if (ev == null || ev.t("processed_at") != null) return;
        String err = null;
        try {
            JsonNode root = Json.tree(ev.s("payload"));
            Set<Long> campaigns = new LinkedHashSet<>();
            for (JsonNode entry : root.path("entry")) {
                for (JsonNode ch : entry.path("changes")) {
                    String field = ch.path("field").asText();
                    JsonNode v = ch.path("value");
                    switch (field) {
                        case "messages" -> messages(v, campaigns);
                        case "message_template_status_update" -> templates.applyStatus(v.path("message_template_id").asText(null),
                                v.path("message_template_name").asText(null), v.path("event").asText(), v.path("reason").asText(null));
                        case "phone_number_quality_update" -> quality(v);
                        default -> log.debug("Ignoring webhook field {}", field);
                    }
                }
            }
            for (long cid : campaigns) refresh(cid);
        } catch (Exception e) {
            err = e.toString().length() > 500 ? e.toString().substring(0, 500) : e.toString();
            log.warn("Webhook {} failed: {}", id, err);
        }
        db.update("update webhook_events set processed_at=:now, error=:e where id=:id", "now", Time.now(), "e", err, "id", id);
    }

    private void refresh(long cid) {
        Row c = db.row("select workspace_id, status from campaigns where id=:id", "id", cid).orElse(null);
        if (c == null) return;
        stats.push(c.l("workspace_id"), cid, c.s("status"), stats.refresh(cid));
    }

    private void messages(JsonNode v, Set<Long> campaigns) {
        String phoneNumberId = v.path("metadata").path("phone_number_id").asText();
        for (JsonNode s : v.path("statuses")) {
            JsonNode e = s.path("errors").path(0);
            applyStatus(s.path("id").asText(), s.path("status").asText(), ts(s.path("timestamp").asText(null)), e.path("code").asText(null),
                    e.path("title").asText(e.path("message").asText(null)), campaigns);
        }
        if (v.path("messages").isEmpty()) return;
        Long ws = db.scalar("select workspace_id from wa_accounts where phone_number_id=:p", Long.class, "p", phoneNumberId);
        if (ws == null) return;
        Map<String, String> names = new HashMap<>();
        for (JsonNode c : v.path("contacts")) names.put(c.path("wa_id").asText(), c.path("profile").path("name").asText(null));
        for (JsonNode m : v.path("messages")) inbound(ws, m, names.get(m.path("from").asText()));
    }

    /** Monotonic status moves: SENT < DELIVERED < READ; FAILED only from QUEUED/SENT. Cost is recorded on delivery. */
    public void applyStatus(String wamid, String status, LocalDateTime at, String errCode, String errMsg, Set<Long> touched) {
        if (wamid == null || wamid.isBlank()) return;
        String st = status == null ? "" : status.toLowerCase();
        db.row("select cm.*, t.category from campaign_messages cm join campaigns c on c.id=cm.campaign_id join wa_templates t on t.id=c.template_id " +
                "where cm.wa_message_id=:w", "w", wamid).ifPresent(cm -> {
            String cur = cm.s("status");
            long id = cm.l("id");
            switch (st) {
                case "sent" -> {
                    if ("QUEUED".equals(cur)) db.update("update campaign_messages set status='SENT', sent_at=:t where id=:id", "t", at, "id", id);
                }
                case "delivered", "read" -> {
                    String target = "read".equals(st) ? "READ" : "DELIVERED";
                    if ("FAILED".equals(cur) || ORDER.indexOf(cur) >= ORDER.indexOf(target)) return;
                    BigDecimal cost = cm.d("cost").signum() == 0 ? rates.sellRate(cm.s("category"), cm.s("phone")) : cm.d("cost");
                    db.update("update campaign_messages set status=:s, cost=:c, delivered_at=coalesce(delivered_at,:t), read_at=if(:s='READ',:t,read_at) where id=:id",
                            "s", target, "c", cost, "t", at, "id", id);
                }
                case "failed" -> {
                    if ("QUEUED".equals(cur) || "SENT".equals(cur)) {
                        db.update("update campaign_messages set status='FAILED', error_code=:c, error_message=:m, failed_at=:t where id=:id",
                                "c", errCode == null ? null : (errCode.length() > 20 ? errCode.substring(0, 20) : errCode), "m", errMsg == null ? null : (errMsg.length() > 255 ? errMsg.substring(0, 255) : errMsg),
                                "t", at, "id", id);
                        wallet.refund(cm.l("workspace_id"), rates.sellRate(cm.s("category"), cm.s("phone")), "CAMPAIGN_MESSAGE", id, "Refund: delivery failed");
                    }
                }
                default -> {}
            }
            if (touched != null) touched.add(cm.l("campaign_id"));
        });
        // inbox thread messages
        String inboxStatus = switch (st) {
            case "sent" -> "SENT";
            case "delivered" -> "DELIVERED";
            case "read" -> "READ";
            case "failed" -> "FAILED";
            default -> null;
        };
        if (inboxStatus != null) {
            db.update("update messages set status=:s where wa_message_id=:w and direction='OUT' and status<>'READ' and (status<>'DELIVERED' or :s in ('READ'))",
                    "s", inboxStatus, "w", wamid);
        }
    }

    private void inbound(long ws, JsonNode m, String profileName) {
        String phone = Phones.e164("+" + m.path("from").asText());
        if (phone == null) return;
        String wamid = m.path("id").asText();
        String type = m.path("type").asText("text");
        String body = switch (type) {
            case "text" -> m.path("text").path("body").asText(null);
            case "button" -> m.path("button").path("text").asText(null);
            case "interactive" -> m.path("interactive").path("button_reply").path("title").asText(m.path("interactive").path("list_reply").path("title").asText(null));
            case "image", "video", "document" -> m.path(type).path("caption").asText(null);
            default -> null;
        };
        String mapped = switch (type) {
            case "text" -> "TEXT";
            case "image" -> "IMAGE";
            case "document" -> "DOCUMENT";
            case "audio", "voice" -> "AUDIO";
            case "video" -> "VIDEO";
            case "button", "interactive" -> "BUTTON";
            case "location" -> "LOCATION";
            default -> "TEXT";
        };
        Row contact = db.row("select * from contacts where workspace_id=:ws and phone=:p", "ws", ws, "p", phone).orElse(null);
        long contactId;
        if (contact == null) {
            try {
                plans.checkContactLimit(ws, 1);
            } catch (ApiException e) {
                return;
            }
            String name = profileName == null || !profileName.matches("^[\\p{L}\\p{M} .'&-]{2,100}$") ? "WhatsApp " + phone.substring(Math.max(0, phone.length() - 4)) : Norm.name(profileName);
            contactId = db.insertInto("contacts", "workspace_id", ws, "full_name", name, "phone", phone, "opt_in_status", "UNKNOWN");
            events.publishEvent(new Events.ContactCreated(ws, contactId));
        } else {
            contactId = contact.l("id");
            if (contact.t("deleted_at") != null) {
                db.update("update contacts set deleted_at=null where id=:id", "id", contactId);
            }
        }
        LocalDateTime at = ts(m.path("timestamp").asText(null));
        if (!inbox.recordInbound(ws, contactId, wamid, mapped, body, null, at)) return; // duplicate
        String norm = body == null ? "" : body.trim().toLowerCase();
        if (STOP_WORDS.contains(norm)) {
            contacts.optOut(ws, contactId);
        } else {
            contacts.optInBySelf(ws, contactId, "WHATSAPP"); // a customer who messages again may opt back in
            events.publishEvent(new Events.InboundMessage(ws, contactId, body));
        }
    }

    private void quality(JsonNode v) {
        String display = v.path("display_phone_number").asText("").replaceAll("[^0-9]", "");
        String q = v.path("quality_rating").asText(null);
        String ev = v.path("event").asText("");
        if (q == null) q = switch (ev.toUpperCase()) {
            case "FLAGGED" -> "RED";
            case "UNFLAGGED", "UPGRADE" -> "GREEN";
            case "DOWNGRADE" -> "YELLOW";
            default -> null;
        };
        if (q == null) return;
        q = q.toUpperCase();
        if (!List.of("GREEN", "YELLOW", "RED").contains(q)) return;
        for (Row a : db.rows("select * from wa_accounts where replace(replace(display_phone,' ',''),'+','') = :d or phone_number_id=:p", "d", display, "p", v.path("phone_number_id").asText(""))) {
            db.update("update wa_accounts set quality_rating=:q, messaging_limit=coalesce(:l,messaging_limit) where id=:id", "q", q, "l", v.path("current_limit").asText(null), "id", a.l("id"));
            if ("RED".equals(q)) {
                notifications.notifyOwners(a.l("workspace_id"), "QUALITY_RED", "WhatsApp quality is RED",
                        "New campaigns are blocked until your number's quality recovers. Reduce frequency and respect opt-outs.", "/settings/whatsapp");
            }
        }
    }

    private static LocalDateTime ts(String epoch) {
        try {
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(epoch)), ZoneOffset.UTC);
        } catch (Exception e) {
            return Time.now();
        }
    }
}
