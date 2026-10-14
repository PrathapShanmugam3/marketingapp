package com.marketingpanel.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import com.marketingpanel.jobs.JobHandler;
import com.marketingpanel.whatsapp.RateService;
import com.marketingpanel.whatsapp.WaException;
import com.marketingpanel.whatsapp.WaGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Job handler for CAMPAIGN_BATCH (SRS 3.3): claim -> rate limit -> send -> record -> update counters + push over WebSocket.
 * A retryable Meta error (429/5xx/circuit open) rethrows so the job queue retries with backoff; other errors fail that message and refund it.
 */
@Component
public class CampaignSender implements JobHandler {
    private static final Logger log = LoggerFactory.getLogger(CampaignSender.class);
    private static final Pattern URL = Pattern.compile("https?://[^\\s]+");

    private final Db db;
    private final WaGateway gw;
    private final WalletService wallet;
    private final RateService rates;
    private final FrequencyCap freq;
    private final CampaignStats stats;
    private final NotificationService notifications;
    private final Settings settings;

    public CampaignSender(Db db, WaGateway gw, WalletService wallet, RateService rates, FrequencyCap freq, CampaignStats stats,
                          NotificationService notifications, Settings settings) {
        this.db = db;
        this.gw = gw;
        this.wallet = wallet;
        this.rates = rates;
        this.freq = freq;
        this.stats = stats;
        this.notifications = notifications;
        this.settings = settings;
    }

    @Override
    public String type() {
        return "CAMPAIGN_BATCH";
    }

    @Override
    public void handle(JsonNode p) {
        long cid = p.get("campaignId").asLong();
        long from = p.get("from").asLong(), to = p.get("to").asLong();
        Row c = db.row("select * from campaigns where id=:id", "id", cid).orElse(null);
        if (c == null) return;
        long ws = c.l("workspace_id");
        String st = c.s("status");
        if (List.of("CANCELLED", "FAILED", "COMPLETED", "PAUSED", "DRAFT", "SCHEDULED").contains(st)) return; // resume re-enqueues
        if ("QUEUED".equals(st)) {
            db.update("update campaigns set status='SENDING', started_at=coalesce(started_at,:now) where id=:id and status='QUEUED'", "now", Time.now(), "id", cid);
        }
        Row acct = gw.account(ws);
        if (acct == null || !"CONNECTED".equals(acct.s("status")) || "RED".equals(acct.s("quality_rating"))) {
            db.update("update campaigns set status='PAUSED', paused_by_system=1 where id=:id and status in ('SENDING','QUEUED')", "id", cid);
            notifications.notifyOwners(ws, "CAMPAIGN_PAUSED", "Campaign " + c.s("name") + " was paused",
                    "Your WhatsApp number is disconnected or its quality is RED.", "/campaigns/" + cid);
            return;
        }
        Row tpl = db.row("select * from wa_templates where id=:id", "id", c.l("template_id")).orElseThrow();
        Map<Long, Row> variantTpl = new HashMap<>();
        for (Row v : db.rows("select v.id, t.* from campaign_variants v join wa_templates t on t.id=v.template_id where v.campaign_id=:c", "c", cid))
            variantTpl.put(v.l("id"), v);
        JsonNode mapping = Json.tree(c.s("variable_mapping_json"));
        Map<String, String> shortCache = new HashMap<>();

        List<Row> msgs = db.rows("select cm.id, cm.variant_id, cm.phone, cm.contact_id, c.full_name, c.email, c.city, c.opt_in_status, c.deleted_at " +
                "from campaign_messages cm join contacts c on c.id=cm.contact_id where cm.campaign_id=:c and cm.id between :f and :t and cm.status='QUEUED' order by cm.id",
                "c", cid, "f", from, "t", to);
        int n = 0;
        for (Row m : msgs) {
            if (n++ % 20 == 0) {
                String cur = db.scalar("select status from campaigns where id=:id", String.class, "id", cid);
                if (!"SENDING".equals(cur)) break; // paused or cancelled mid-batch
            }
            long mid = m.l("id");
            String phone = m.s("phone");
            Row useTpl = m.lo("variant_id") != null && variantTpl.containsKey(m.l("variant_id")) ? variantTpl.get(m.l("variant_id")) : tpl;
            String category = useTpl.s("category");
            if (m.t("deleted_at") != null || !"OPTED_IN".equals(m.s("opt_in_status"))) {
                fail(ws, mid, category, phone, "OPTED_OUT", "Opted out", true);
                continue;
            }
            if ("MARKETING".equals(category) && freq.exceeded(ws, m.l("contact_id"))) {
                fail(ws, mid, category, phone, "FREQ_CAP", "Frequency cap reached", true);
                continue;
            }
            List<String> params;
            try {
                params = VariableResolver.resolve(mapping, useTpl.i("variable_count"), m);
            } catch (IllegalArgumentException e) {
                fail(ws, mid, category, phone, "VARIABLE", e.getMessage(), true);
                continue;
            }
            if (c.b("track_links")) params = trackLinks(ws, cid, params, m.l("contact_id"), shortCache);
            try {
                String wamid = gw.sendTemplate(acct, WaGateway.build(useTpl, params, c.s("header_media_url"), null), phone);
                db.update("update campaign_messages set status='SENT', wa_message_id=:w, sent_at=:now where id=:id and status='QUEUED'", "w", wamid, "now", Time.now(), "id", mid);
            } catch (WaException e) {
                if (e.retryable()) throw e;
                fail(ws, mid, category, phone, e.code(), e.getMessage(), true);
            }
        }
        finishBatch(c);
    }

    private void fail(long ws, long mid, String category, String phone, String code, String msg, boolean refund) {
        int n = db.update("update campaign_messages set status='FAILED', error_code=:c, error_message=:m, failed_at=:now where id=:id and status in ('QUEUED','SENT')",
                "c", code.length() > 20 ? code.substring(0, 20) : code, "m", msg == null ? null : (msg.length() > 255 ? msg.substring(0, 255) : msg), "now", Time.now(), "id", mid);
        if (n > 0 && refund) wallet.refund(ws, rates.sellRate(category, phone), "CAMPAIGN_MESSAGE", mid, "Refund: message not sent");
    }

    List<String> trackLinks(long ws, long cid, List<String> params, long contactId, Map<String, String> cache) {
        List<String> out = new ArrayList<>();
        for (String p : params) {
            Matcher m = URL.matcher(p);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String url = m.group();
                String code = cache.computeIfAbsent(url, u -> shortCode(ws, cid, u));
                m.appendReplacement(sb, Matcher.quoteReplacement(settings.publicBaseUrl + "/l/" + code + "?c=" + contactId));
            }
            m.appendTail(sb);
            out.add(sb.toString());
        }
        return out;
    }

    private String shortCode(long ws, long cid, String url) {
        String existing = db.scalar("select code from short_links where campaign_id=:c and destination_url=:u limit 1", String.class, "c", cid, "u", url);
        if (existing != null) return existing;
        String code;
        do {
            code = Ids.randomCode(7);
        } while (db.exists("select 1 from short_links where code=:c", "c", code));
        db.insertInto("short_links", "workspace_id", ws, "code", code, "type", "URL", "destination_url", url, "campaign_id", cid, "utm_source", "whatsapp", "utm_medium", "campaign");
        return code;
    }

    private void finishBatch(Row c) {
        long cid = c.l("id");
        long ws = c.l("workspace_id");
        Map<String, Object> counters = stats.refresh(cid);
        String status = db.scalar("select status from campaigns where id=:id", String.class, "id", cid);
        if ("SENDING".equals(status) && ((Number) counters.get("queued")).longValue() == 0) {
            int n = db.update("update campaigns set status='COMPLETED', completed_at=:now where id=:id and status='SENDING'", "now", Time.now(), "id", cid);
            if (n > 0) {
                status = "COMPLETED";
                notifications.notifyOwners(ws, "CAMPAIGN_DONE", "Campaign " + c.s("name") + " finished",
                        counters.get("sent") + " sent, " + counters.get("failed") + " failed.", "/campaigns/" + cid);
            }
        }
        stats.push(ws, cid, status, counters);
    }

    /** Batch exhausted its retries (e.g. Meta down > 30 min): pause the campaign and tell the owner. */
    @Override
    public void onDead(JsonNode p, String error) {
        long cid = p.get("campaignId").asLong();
        Row c = db.row("select * from campaigns where id=:id", "id", cid).orElse(null);
        if (c == null) return;
        int n = db.update("update campaigns set status='PAUSED', paused_by_system=1 where id=:id and status in ('SENDING','QUEUED')", "id", cid);
        if (n > 0) {
            notifications.notifyOwners(c.l("workspace_id"), "CAMPAIGN_PAUSED", "Campaign " + c.s("name") + " was paused",
                    "Sending kept failing, so we paused it. Resume it when WhatsApp is reachable.", "/campaigns/" + cid);
            log.warn("Campaign {} paused after dead batch: {}", cid, error);
        }
    }
}
