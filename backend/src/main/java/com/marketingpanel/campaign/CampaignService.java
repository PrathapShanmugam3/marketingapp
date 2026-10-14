package com.marketingpanel.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.auth.AuthUser;
import com.marketingpanel.common.*;
import com.marketingpanel.crm.AudienceQuery;
import com.marketingpanel.jobs.JobService;
import com.marketingpanel.whatsapp.RateService;
import com.marketingpanel.whatsapp.WaGateway;
import com.marketingpanel.whatsapp.WaException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Campaign lifecycle (SRS 3.4): DRAFT -> SCHEDULED/QUEUED -> SENDING -> COMPLETED/FAILED, with PAUSED and CANCELLED.
 * Any other transition is rejected with 409 INVALID_STATE.
 */
@Service
public class CampaignService {
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "DRAFT", Set.of("SCHEDULED", "QUEUED"),
            "SCHEDULED", Set.of("QUEUED", "CANCELLED", "PAUSED"),
            "QUEUED", Set.of("SENDING", "PAUSED"),
            "SENDING", Set.of("COMPLETED", "FAILED", "PAUSED"),
            "PAUSED", Set.of("SENDING", "CANCELLED", "SCHEDULED", "QUEUED"),
            "COMPLETED", Set.of(), "CANCELLED", Set.of(), "FAILED", Set.of());
    private static final Map<String, Long> TIER = Map.of("TIER_50", 50L, "TIER_250", 250L, "TIER_1K", 1000L, "TIER_2K", 2000L,
            "TIER_10K", 10_000L, "TIER_100K", 100_000L, "TIER_UNLIMITED", Long.MAX_VALUE);

    private final Db db;
    private final WaGateway gw;
    private final WalletService wallet;
    private final RateService rates;
    private final JobService jobs;
    private final AuditService audit;
    private final NotificationService notifications;
    private final CampaignStats stats;

    public CampaignService(Db db, WaGateway gw, WalletService wallet, RateService rates, JobService jobs, AuditService audit,
                           NotificationService notifications, CampaignStats stats) {
        this.db = db;
        this.gw = gw;
        this.wallet = wallet;
        this.rates = rates;
        this.jobs = jobs;
        this.audit = audit;
        this.notifications = notifications;
        this.stats = stats;
    }

    // ------------------------------------------------------------ read

    public List<Map<String, Object>> list(long ws, String status, String q) {
        StringBuilder sb = new StringBuilder("select c.*, t.name as template_name, t.category from campaigns c join wa_templates t on t.id=c.template_id where c.workspace_id=:ws");
        List<Object> kv = new ArrayList<>(List.of("ws", ws));
        if (status != null && !status.isBlank()) {
            sb.append(" and c.status=:st");
            kv.add("st");
            kv.add(status);
        }
        if (q != null && !q.isBlank()) {
            if (q.trim().length() < 2) throw ApiException.invalid("q", "search.min", "Type at least 2 characters.");
            sb.append(" and c.name like :q");
            kv.add("q");
            kv.add("%" + q.trim() + "%");
        }
        return db.list(sb + " order by c.id desc limit 200", kv.toArray());
    }

    public Map<String, Object> get(long ws, long id) {
        Row c = load(ws, id);
        Map<String, Object> m = db.one("select c.*, t.name as template_name, t.category, t.body as template_body from campaigns c join wa_templates t on t.id=c.template_id " +
                "where c.id=:id", "id", id).orElseThrow();
        m.put("variants", db.list("select v.*, t.name as template_name from campaign_variants v join wa_templates t on t.id=v.template_id where v.campaign_id=:id order by v.label", "id", id));
        m.put("actions", allowedActions(c.s("status"), c));
        return m;
    }

    private List<String> allowedActions(String status, Row c) {
        return switch (status) {
            case "DRAFT" -> List.of("send", "schedule", "edit", "delete");
            case "SCHEDULED" -> List.of("edit", "cancel");
            case "SENDING" -> List.of("pause");
            case "PAUSED" -> List.of("resume", "cancel");
            case "COMPLETED", "CANCELLED", "FAILED" -> List.of("duplicate");
            default -> List.of();
        };
    }

    Row load(long ws, long id) {
        return db.row("select * from campaigns where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Campaign"));
    }

    // ----------------------------------------------------- create / edit

    private record Validated(Row template, List<Row> variantTemplates, LocalDateTime scheduledAt) {}

    private Validated validate(long ws, CampaignDtos.CampaignRequest r, Long exceptId, boolean wantSchedule) {
        List<FieldErr> errs = new ArrayList<>();
        if (db.exists("select 1 from campaigns where workspace_id=:ws and lower(name)=lower(:n)" + (exceptId != null ? " and id<>:id" : ""), "ws", ws, "n", r.name(), "id", exceptId == null ? 0L : exceptId))
            errs.add(new FieldErr("name", "campaign.name.exists", "You already have a campaign with this name."));
        Row acct = gw.account(ws);
        Row tpl = db.row("select * from wa_templates where id=:id and workspace_id=:ws and status='APPROVED'", "id", r.templateId(), "ws", ws).orElse(null);
        if (tpl == null || acct == null || tpl.l("wa_account_id") != acct.l("id"))
            errs.add(new FieldErr("templateId", "campaign.template", "Choose an approved template."));
        try {
            AudienceQuery.forCampaign(r.audienceType(), r.audience(), ws, id -> segmentFilter(ws, id));
        } catch (ApiException e) {
            errs.addAll(e.fieldErrors());
        }
        List<Row> variantTemplates = new ArrayList<>();
        if (tpl != null) {
            VariableResolver.validate(r.variableMapping(), tpl.i("variable_count"), errs);
            boolean mediaHeader = List.of("IMAGE", "VIDEO", "DOCUMENT").contains(tpl.s("header_type"));
            if (mediaHeader && (r.headerMediaUrl() == null || r.headerMediaUrl().isBlank()) && tpl.s("header_media_url") == null)
                errs.add(new FieldErr("headerMediaUrl", "campaign.header", "Upload the header image."));
            var ab = r.abTest();
            if (ab != null && ab.enabled()) {
                List<CampaignDtos.Variant> vs = ab.variants() == null ? List.of() : ab.variants();
                if (vs.size() < 2 || vs.size() > 3) errs.add(new FieldErr("abTest", "campaign.ab.count", "An A/B test needs 2 or 3 variants."));
                int total = vs.stream().mapToInt(CampaignDtos.Variant::splitPercent).sum();
                if (total != 100) errs.add(new FieldErr("abTest", "campaign.ab.split", "Variant splits must add up to 100%."));
                for (CampaignDtos.Variant v : vs) {
                    Row t = db.row("select * from wa_templates where id=:id and workspace_id=:ws and status='APPROVED'", "id", v.templateId(), "ws", ws).orElse(null);
                    if (t == null || !t.s("category").equals(tpl.s("category")) || t.i("variable_count") != tpl.i("variable_count")) {
                        errs.add(new FieldErr("abTest", "campaign.ab.template", "Variants must be approved templates of the same category and variable count."));
                        break;
                    }
                    variantTemplates.add(t);
                }
                if (!List.of("READ", "CLICK").contains(ab.metric())) errs.add(new FieldErr("abTest", "campaign.ab.metric", "Choose the winner metric."));
                if (ab.testPercent() == null || ab.testPercent() < 10 || ab.testPercent() > 50) errs.add(new FieldErr("abTest", "campaign.ab.test", "Test group must be 10 to 50% of the audience."));
                if (ab.winnerAfterHours() == null || ab.winnerAfterHours() < 1 || ab.winnerAfterHours() > 24) errs.add(new FieldErr("abTest", "campaign.ab.hours", "Winner is picked after 1 to 24 hours."));
            }
        }
        LocalDateTime when = null;
        if (r.scheduledAt() != null && !r.scheduledAt().isBlank()) {
            when = Time.parseUtc(r.scheduledAt());
            if (when.isBefore(Time.now().plusMinutes(10)) || when.isAfter(Time.now().plusDays(90)))
                errs.add(new FieldErr("scheduledAt", "vr.future.invalid", "Choose a time at least 10 minutes from now."));
        } else if (wantSchedule) {
            errs.add(new FieldErr("scheduledAt", "vr.future.invalid", "Choose a time at least 10 minutes from now."));
        }
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        return new Validated(tpl, variantTemplates, when);
    }

    String segmentFilter(long ws, long id) {
        return db.scalar("select filter_json from segments where id=:id and workspace_id=:ws", String.class, "id", id, "ws", ws);
    }

    public Map<String, Object> create(long ws, long userId, CampaignDtos.CampaignRequest r) {
        Validated v = validate(ws, r, null, false);
        long id = db.tx(() -> {
            var ab = r.abTest();
            boolean abOn = ab != null && ab.enabled();
            long cid = db.insertInto("campaigns", "workspace_id", ws, "name", r.name(), "template_id", abOn ? ab.variants().get(0).templateId() : r.templateId(),
                    "audience_type", r.audienceType(), "audience_json", r.audience() == null ? null : r.audience().toString(),
                    "variable_mapping_json", r.variableMapping().toString(), "header_media_url", blank(r.headerMediaUrl()), "scheduled_at", v.scheduledAt(),
                    "status", "DRAFT", "created_by", userId, "track_links", r.trackLinks(),
                    "ab_metric", abOn ? ab.metric() : null, "ab_test_percent", abOn ? ab.testPercent() : null, "ab_winner_after_hours", abOn ? ab.winnerAfterHours() : null);
            saveVariants(cid, r);
            return cid;
        });
        audit.log("CAMPAIGN_CREATED", "campaigns", id);
        return get(ws, id);
    }

    private void saveVariants(long cid, CampaignDtos.CampaignRequest r) {
        db.update("delete from campaign_variants where campaign_id=:c", "c", cid);
        var ab = r.abTest();
        if (ab == null || !ab.enabled()) return;
        String[] labels = {"A", "B", "C"};
        for (int i = 0; i < ab.variants().size(); i++) {
            db.insertInto("campaign_variants", "campaign_id", cid, "label", labels[i], "template_id", ab.variants().get(i).templateId(),
                    "split_percent", ab.variants().get(i).splitPercent());
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public Map<String, Object> update(long ws, long id, CampaignDtos.CampaignRequest r) {
        Row c = load(ws, id);
        if (!List.of("DRAFT", "SCHEDULED").contains(c.s("status"))) throw ApiException.state("Only draft or scheduled campaigns can be edited.");
        Validated v = validate(ws, r, id, "SCHEDULED".equals(c.s("status")));
        if ("SCHEDULED".equals(c.s("status"))) { // edit of a scheduled campaign: release the reservation, user re-confirms
            releaseReservation(c);
            db.update("update campaigns set status='DRAFT', estimated_cost=0 where id=:id", "id", id);
        }
        var ab = r.abTest();
        boolean abOn = ab != null && ab.enabled();
        db.update("update campaigns set name=:n, template_id=:t, audience_type=:at, audience_json=:aj, variable_mapping_json=:vm, header_media_url=:h, scheduled_at=:s, " +
                        "track_links=:tl, ab_metric=:am, ab_test_percent=:ap, ab_winner_after_hours=:ah where id=:id",
                "n", r.name(), "t", abOn ? ab.variants().get(0).templateId() : r.templateId(), "at", r.audienceType(), "aj", r.audience() == null ? null : r.audience().toString(),
                "vm", r.variableMapping().toString(), "h", blank(r.headerMediaUrl()), "s", v.scheduledAt(), "tl", r.trackLinks(),
                "am", abOn ? ab.metric() : null, "ap", abOn ? ab.testPercent() : null, "ah", abOn ? ab.winnerAfterHours() : null, "id", id);
        saveVariants(id, r);
        return get(ws, id);
    }

    public void delete(long ws, long id) {
        Row c = load(ws, id);
        if (!"DRAFT".equals(c.s("status"))) throw ApiException.state("Only drafts can be deleted.");
        db.update("delete from campaigns where id=:id", "id", id);
    }

    // ----------------------------------------------------------- estimate

    public record Estimate(long recipients, BigDecimal cost, BigDecimal balance, boolean sufficient, long messagingLimit, boolean withinLimit,
                           List<String> warnings) {}

    private AudienceQuery audienceOf(long ws, Row c) {
        JsonNode aud = c.s("audience_json") == null ? null : Json.tree(c.s("audience_json"));
        return AudienceQuery.forCampaign(c.s("audience_type"), aud, ws, id -> segmentFilter(ws, id));
    }

    private Object[] kv(AudienceQuery q, long ws) {
        Map<String, Object> p = new LinkedHashMap<>(q.params);
        p.put("ws", ws);
        Object[] out = new Object[p.size() * 2];
        int i = 0;
        for (var e : p.entrySet()) {
            out[i++] = e.getKey();
            out[i++] = e.getValue();
        }
        return out;
    }

    public Estimate estimate(long ws, long id) {
        Row c = load(ws, id);
        Row tpl = db.row("select category from wa_templates where id=:id", "id", c.l("template_id")).orElseThrow();
        AudienceQuery q = audienceOf(ws, c);
        long recipients = db.count("select count(*) from contacts c where " + q.sql, kv(q, ws));
        BigDecimal cost = costFor(tpl.s("category"), q, ws);
        BigDecimal balance = wallet.balance(ws);
        long limit = messagingLimit(ws);
        List<String> warnings = new ArrayList<>();
        if (c.t("scheduled_at") != null) {
            int hour = Time.toIst(c.t("scheduled_at")).getHour();
            if (hour < 9 || hour >= 21) warnings.add("Scheduled outside the recommended 9 AM to 9 PM IST window.");
        }
        return new Estimate(recipients, cost, balance, balance.compareTo(cost) >= 0, limit, recipients <= limit, warnings);
    }

    private BigDecimal costFor(String category, AudienceQuery q, long ws) {
        long india = db.count("select count(*) from contacts c where " + q.sql + " and c.phone like '+91%'", kv(q, ws));
        BigDecimal total = rates.sellRate(category, "+919000000000").multiply(BigDecimal.valueOf(india));
        for (Row r : db.rows("select c.phone from contacts c where " + q.sql + " and c.phone not like '+91%'", kv(q, ws)))
            total = total.add(rates.sellRate(category, r.s("phone")));
        return Money.rate(total);
    }

    private long messagingLimit(long ws) {
        Row a = gw.account(ws);
        if (a == null || a.s("messaging_limit") == null) return Long.MAX_VALUE;
        return TIER.getOrDefault(a.s("messaging_limit"), Long.MAX_VALUE);
    }

    // ------------------------------------------------------------ actions

    public Map<String, Object> act(AuthUser me, long id, String action, Map<String, Object> body) {
        long ws = me.workspaceId();
        Row c = load(ws, id);
        switch (action) {
            case "send" -> confirm(ws, c, false);
            case "schedule" -> confirm(ws, c, true);
            case "pause" -> transition(c, "PAUSED", "SENDING");
            case "resume" -> resume(ws, c);
            case "cancel" -> cancel(ws, c);
            case "duplicate" -> {
                return duplicate(ws, me.userId(), c);
            }
            default -> throw ApiException.badRequest("Unknown action.");
        }
        audit.log("CAMPAIGN_" + action.toUpperCase(), "campaigns", id);
        Map<String, Object> out = get(ws, id);
        stats.push(ws, id, (String) out.get("status"), stats.refresh(id));
        return out;
    }

    private void transition(Row c, String to, String requiredFrom) {
        if (requiredFrom != null && !requiredFrom.equals(c.s("status"))) throw ApiException.state("This action is not allowed while the campaign is " + c.s("status") + ".");
        if (!ALLOWED.get(c.s("status")).contains(to)) throw ApiException.state("A " + c.s("status") + " campaign cannot become " + to + ".");
        int n = db.update("update campaigns set status=:to where id=:id and status=:from", "to", to, "id", c.l("id"), "from", c.s("status"));
        if (n == 0) throw ApiException.state("The campaign changed. Refresh and try again.");
    }

    private void checkSendable(long ws, Row c) {
        gw.sendable(ws);
        String st = db.scalar("select status from workspaces where id=:ws", String.class, "ws", ws);
        if (!List.of("ACTIVE", "TRIAL").contains(st)) throw ApiException.forbidden("Your workspace cannot send messages right now. Check billing.");
        Row sub = db.row("select current_period_end from subscriptions where workspace_id=:ws and status in ('ACTIVE','PENDING','HALTED') order by id desc limit 1", "ws", ws).orElse(null);
        if (sub != null && sub.t("current_period_end") != null && sub.t("current_period_end").plusDays(7).isBefore(Time.now()) && "PAST_DUE".equals(st))
            throw ApiException.forbidden("Sending is blocked: your subscription payment is overdue.");
    }

    /** Confirm = server re-validates everything, reserves the wallet amount and queues or schedules. */
    private void confirm(long ws, Row c, boolean schedule) {
        if (!"DRAFT".equals(c.s("status"))) throw ApiException.state("Only draft campaigns can be sent or scheduled.");
        checkSendable(ws, c);
        Row tpl = db.row("select * from wa_templates where id=:id", "id", c.l("template_id")).orElseThrow();
        if (!"APPROVED".equals(tpl.s("status"))) throw ApiException.invalid("templateId", "campaign.template", "Choose an approved template.");
        if (schedule) {
            if (c.t("scheduled_at") == null || c.t("scheduled_at").isBefore(Time.now().plusMinutes(10)))
                throw ApiException.invalid("scheduledAt", "vr.future.invalid", "Choose a time at least 10 minutes from now.");
        }
        AudienceQuery q = audienceOf(ws, c);
        long recipients = db.count("select count(*) from contacts c where " + q.sql, kv(q, ws));
        if (recipients < 1) throw ApiException.invalid("audience", "campaign.recipients", "No opted-in contacts match this audience.");
        if (recipients > messagingLimit(ws)) throw ApiException.invalid("audience", "campaign.limit", "Recipients exceed your number's Meta messaging limit.");
        if (!schedule && db.exists("select 1 from campaigns where workspace_id=:ws and id<>:id and status='SENDING' and audience_type=:t and " +
                "coalesce(cast(audience_json as char),'')=coalesce(:a,'') limit 1", "ws", ws, "id", c.l("id"), "t", c.s("audience_type"), "a", c.s("audience_json")))
            throw ApiException.state("Another campaign for the same audience is already sending.");
        BigDecimal cost = costFor(tpl.s("category"), q, ws);
        wallet.debit(ws, cost, "CAMPAIGN", c.l("id"), "Reserved for campaign " + c.s("name"), null);
        db.update("update campaigns set estimated_cost=:e, total_recipients=:r where id=:id", "e", cost, "r", recipients, "id", c.l("id"));
        if (schedule) {
            transition(c, "SCHEDULED", "DRAFT");
        } else {
            queueRecipients(ws, c.l("id"));
        }
    }

    /** Materialises recipients, assigns A/B variants and enqueues one CAMPAIGN_BATCH job per 100 recipients. */
    public void queueRecipients(long ws, long campaignId) {
        Row c = load(ws, campaignId);
        AudienceQuery q = audienceOf(ws, c);
        List<Row> variants = db.rows("select id, split_percent from campaign_variants where campaign_id=:c order by label", "c", campaignId);
        String variantExpr = "null";
        if (!variants.isEmpty()) {
            StringBuilder sb = new StringBuilder("(case ");
            int acc = 0;
            for (int i = 0; i < variants.size() - 1; i++) {
                acc += variants.get(i).i("split_percent");
                sb.append("when floor(rand()*100) < ").append(acc).append(" then ").append(variants.get(i).l("id")).append(' ');
            }
            sb.append("else ").append(variants.get(variants.size() - 1).l("id")).append(" end)");
            variantExpr = sb.toString();
        }
        Map<String, Object> p = new LinkedHashMap<>(q.params);
        p.put("ws", ws);
        p.put("cid", campaignId);
        db.updateMap("insert ignore into campaign_messages (campaign_id, workspace_id, contact_id, variant_id, phone) " +
                "select :cid, c.workspace_id, c.id, " + variantExpr + ", c.phone from contacts c where " + q.sql, p);
        long total = db.count("select count(*) from campaign_messages where campaign_id=:c", "c", campaignId);
        Row tpl = db.row("select category from wa_templates where id=:id", "id", c.l("template_id")).orElseThrow();
        // Reconcile the reservation with the recipients actually materialised (scheduled campaigns may change).
        BigDecimal actualCost = Money.rate(sumRates(tpl.s("category"), campaignId));
        BigDecimal reserved = c.d("estimated_cost");
        BigDecimal diff = actualCost.subtract(reserved);
        if (diff.signum() > 0) {
            try {
                wallet.debit(ws, diff, "CAMPAIGN", campaignId, "Extra reservation for campaign " + c.s("name"), null);
            } catch (ApiException e) {
                wallet.refund(ws, reserved, "CAMPAIGN", campaignId, "Campaign could not start: wallet too low");
                db.update("update campaigns set status='FAILED' where id=:id", "id", campaignId);
                notifications.notifyOwners(ws, "CAMPAIGN_FAILED", "Campaign " + c.s("name") + " did not start", "Your wallet balance was too low at send time.", "/campaigns/" + campaignId);
                return;
            }
        } else if (diff.signum() < 0) {
            wallet.refund(ws, diff.abs(), "CAMPAIGN", campaignId, "Released unused reservation for campaign " + c.s("name"));
        }
        db.update("update campaigns set total_recipients=:t, estimated_cost=:e, status=if(status in ('DRAFT','SCHEDULED','PAUSED'),'QUEUED',status) where id=:id",
                "t", total, "e", actualCost, "id", campaignId);
        enqueueBatches(campaignId, 0);
    }

    private BigDecimal sumRates(String category, long campaignId) {
        BigDecimal india = db.scalar("select count(*) from campaign_messages where campaign_id=:c and phone like '+91%'", BigDecimal.class, "c", campaignId);
        BigDecimal total = rates.sellRate(category, "+919000000000").multiply(india == null ? BigDecimal.ZERO : india);
        for (Row r : db.rows("select phone from campaign_messages where campaign_id=:c and phone not like '+91%'", "c", campaignId))
            total = total.add(rates.sellRate(category, r.s("phone")));
        return total;
    }

    /** Keyset-walks QUEUED messages and enqueues a job per 100. */
    void enqueueBatches(long campaignId, long afterId) {
        long last = afterId;
        while (true) {
            List<Row> ids = db.rows("select id from campaign_messages where campaign_id=:c and status='QUEUED' and id>:a order by id limit 100", "c", campaignId, "a", last);
            if (ids.isEmpty()) break;
            long from = ids.get(0).l("id"), to = ids.get(ids.size() - 1).l("id");
            jobs.enqueue("CAMPAIGN_BATCH", Map.of("campaignId", campaignId, "from", from, "to", to), Time.now(), 3, 5);
            last = to;
        }
    }

    private void resume(long ws, Row c) {
        if (!"PAUSED".equals(c.s("status"))) throw ApiException.state("Only a paused campaign can be resumed.");
        checkSendable(ws, c);
        if (c.t("started_at") != null) { // was sending
            transition(c, "SENDING", "PAUSED");
            db.update("update campaigns set paused_by_system=0 where id=:id", "id", c.l("id"));
            enqueueBatches(c.l("id"), 0);
        } else if (c.i("total_recipients") == 0 || db.count("select count(*) from campaign_messages where campaign_id=:c", "c", c.l("id")) == 0) {
            // paused while scheduled: back to SCHEDULED, or queue now if the time already passed
            boolean future = c.t("scheduled_at") != null && c.t("scheduled_at").isAfter(Time.now());
            db.update("update campaigns set paused_by_system=0 where id=:id", "id", c.l("id"));
            if (future) transition(c, "SCHEDULED", "PAUSED");
            else queueRecipients(ws, c.l("id"));
        } else {
            db.update("update campaigns set status='QUEUED', paused_by_system=0 where id=:id", "id", c.l("id"));
            enqueueBatches(c.l("id"), 0);
        }
    }

    private void cancel(long ws, Row c) {
        if (!List.of("SCHEDULED", "PAUSED").contains(c.s("status"))) throw ApiException.state("Only a scheduled or paused campaign can be cancelled.");
        transition(c, "CANCELLED", c.s("status"));
        releaseReservation(c);
        db.update("update campaigns set completed_at=:now where id=:id", "now", Time.now(), "id", c.l("id"));
    }

    /** Refunds the part of the reservation that was never sent (all of it when nothing was queued). */
    private void releaseReservation(Row c) {
        long ws = c.l("workspace_id");
        long id = c.l("id");
        BigDecimal reserved = c.d("estimated_cost");
        if (reserved == null || reserved.signum() == 0) return;
        Row tpl = db.row("select category from wa_templates where id=:id", "id", c.l("template_id")).orElseThrow();
        BigDecimal back;
        if (db.count("select count(*) from campaign_messages where campaign_id=:c", "c", id) == 0) {
            back = reserved;
        } else {
            back = BigDecimal.ZERO;
            for (Row m : db.rows("select phone from campaign_messages where campaign_id=:c and status='QUEUED'", "c", id))
                back = back.add(rates.sellRate(tpl.s("category"), m.s("phone")));
            db.update("delete from campaign_messages where campaign_id=:c and status='QUEUED'", "c", id);
        }
        back = Money.rate(back.min(reserved));
        if (back.signum() > 0) wallet.refund(ws, back, "CAMPAIGN", id, "Released unused reservation for campaign " + c.s("name"));
        db.update("update campaigns set estimated_cost=estimated_cost-:b where id=:id", "b", back, "id", id);
    }

    private Map<String, Object> duplicate(long ws, long userId, Row c) {
        String base = c.s("name") + " (copy)";
        String name = base;
        int i = 2;
        while (db.exists("select 1 from campaigns where workspace_id=:ws and lower(name)=lower(:n)", "ws", ws, "n", name)) name = base + " " + i++;
        long id = db.insertInto("campaigns", "workspace_id", ws, "name", name, "template_id", c.l("template_id"), "audience_type", c.s("audience_type"),
                "audience_json", c.s("audience_json"), "variable_mapping_json", c.s("variable_mapping_json"), "header_media_url", c.s("header_media_url"),
                "status", "DRAFT", "created_by", userId, "track_links", c.b("track_links"), "ab_metric", c.s("ab_metric"),
                "ab_test_percent", c.lo("ab_test_percent"), "ab_winner_after_hours", c.lo("ab_winner_after_hours"));
        for (Row v : db.rows("select * from campaign_variants where campaign_id=:c", "c", c.l("id")))
            db.insertInto("campaign_variants", "campaign_id", id, "label", v.s("label"), "template_id", v.l("template_id"), "split_percent", v.i("split_percent"));
        return get(ws, id);
    }

    // ------------------------------------------------------------ test send

    public Map<String, Object> testSend(long ws, long userId, long id, String phoneRaw) {
        Row c = load(ws, id);
        String phone = Phones.e164(phoneRaw);
        if (phone == null) throw ApiException.invalid("phone", "vr.mobile.invalid", "Enter a valid 10-digit mobile number.");
        long used = db.count("select count(*) from audit_logs where workspace_id=:ws and action='CAMPAIGN_TEST_SEND' and entity_type='campaigns' and entity_id=:id", "ws", ws, "id", id);
        if (used >= 5) throw ApiException.state("You can send at most 5 test messages per campaign.");
        Row acct = gw.sendable(ws);
        Row tpl = db.row("select * from wa_templates where id=:id and status='APPROVED'", "id", c.l("template_id")).orElseThrow(() -> ApiException.state("The template is not approved."));
        Row sample = new Row(Map.of("full_name", "Test Customer", "phone", phone, "email", "test@example.com", "city", "Trichy"));
        List<String> params;
        try {
            params = VariableResolver.resolve(Json.tree(c.s("variable_mapping_json")), tpl.i("variable_count"), sample);
        } catch (IllegalArgumentException e) {
            throw VariableResolver.bad("Map every variable or give fallback text.");
        }
        BigDecimal rate = rates.sellRate(tpl.s("category"), phone);
        wallet.debit(ws, rate, "CAMPAIGN", id, "Test message", userId);
        try {
            String wamid = gw.sendTemplate(acct, WaGateway.build(tpl, params, c.s("header_media_url"), null), phone);
            audit.log("CAMPAIGN_TEST_SEND", "campaigns", id, null, Map.of("to", Phones.mask(phone)));
            return Map.of("ok", true, "wamid", wamid, "remaining", 4 - used);
        } catch (WaException e) {
            wallet.refund(ws, rate, "CAMPAIGN", id, "Test message failed");
            throw ApiException.gateway("The test message could not be sent: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------- report

    public Map<String, Object> messages(long ws, long id, String status, String q, Long cursor, int size) {
        load(ws, id);
        size = Math.min(Math.max(size, 1), 100);
        StringBuilder sb = new StringBuilder("select cm.id, cm.contact_id, cm.phone, cm.status, cm.error_code, cm.error_message, cm.cost, cm.sent_at, cm.delivered_at, cm.read_at, cm.failed_at, " +
                "c.full_name, v.label as variant from campaign_messages cm join contacts c on c.id=cm.contact_id left join campaign_variants v on v.id=cm.variant_id where cm.campaign_id=:id");
        List<Object> kv = new ArrayList<>(List.of("id", id));
        if (status != null && !status.isBlank()) {
            sb.append(" and cm.status=:st");
            kv.add("st");
            kv.add(status);
        }
        if (q != null && !q.isBlank()) {
            if (q.trim().length() < 2) throw ApiException.invalid("q", "search.min", "Type at least 2 characters.");
            sb.append(" and (c.full_name like :q or cm.phone like :q)");
            kv.add("q");
            kv.add("%" + q.trim() + "%");
        }
        if (cursor != null) {
            sb.append(" and cm.id > :cur");
            kv.add("cur");
            kv.add(cursor);
        }
        sb.append(" order by cm.id limit ").append(size + 1);
        List<Row> rows = db.rows(sb.toString(), kv.toArray());
        boolean more = rows.size() > size;
        if (more) rows = rows.subList(0, size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Row r : rows) {
            Map<String, Object> m = r.api();
            m.put("reason", plainReason(r.s("error_code"), r.s("error_message")));
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("nextCursor", more ? rows.get(rows.size() - 1).l("id") : null);
        return out;
    }

    static String plainReason(String code, String msg) {
        if (code == null) return null;
        return switch (code) {
            case "131026" -> "The number is not on WhatsApp or cannot receive messages.";
            case "131047" -> "The 24-hour window closed; a template is required.";
            case "131049" -> "Meta limited marketing messages to this person to keep quality high.";
            case "130429", "131056" -> "Sending too fast. We will retry.";
            case "131000", "131021" -> "WhatsApp could not deliver this message.";
            case "OPTED_OUT" -> "The customer opted out before this message was sent.";
            case "FREQ_CAP" -> "Skipped: the customer already received the maximum marketing messages in 24 hours.";
            case "VARIABLE" -> "A template variable had no value for this contact.";
            default -> msg == null ? "Delivery failed (code " + code + ")." : msg;
        };
    }

    public String messagesCsv(long ws, long id) {
        load(ws, id);
        StringBuilder sb = new StringBuilder("name,phone,status,error_code,reason,cost,sent_at,delivered_at,read_at\n");
        for (Row r : db.rows("select cm.*, c.full_name from campaign_messages cm join contacts c on c.id=cm.contact_id where cm.campaign_id=:id order by cm.id", "id", id)) {
            sb.append(com.marketingpanel.crm.ContactService.csv(r.s("full_name"))).append(',').append(r.s("phone")).append(',').append(r.s("status")).append(',')
                    .append(r.s("error_code") == null ? "" : r.s("error_code")).append(',')
                    .append(com.marketingpanel.crm.ContactService.csv(plainReason(r.s("error_code"), r.s("error_message")))).append(',').append(r.d("cost")).append(',')
                    .append(r.t("sent_at") == null ? "" : r.t("sent_at")).append(',').append(r.t("delivered_at") == null ? "" : r.t("delivered_at")).append(',')
                    .append(r.t("read_at") == null ? "" : r.t("read_at")).append('\n');
        }
        audit.log("CAMPAIGN_REPORT_EXPORTED", "campaigns", id);
        return sb.toString();
    }
}
