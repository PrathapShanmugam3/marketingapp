package com.marketingpanel.engage;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.common.*;
import com.marketingpanel.crm.AudienceQuery;
import com.marketingpanel.jobs.JobService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/** E-mail, SMS and web-push broadcasts to opted-in contacts, with per-channel unsubscribe, wallet billing for SMS and daily e-mail limits. */
@Service
public class BroadcastService {
    private static final int MAX_SMS_CHARS = 306;
    private final Db db;
    private final PlanService plans;
    private final WalletService wallet;
    private final JobService jobs;
    private final AuditService audit;
    private final Notifier notifier;
    private final Unsubscribe unsub;
    private final BigDecimal smsRate;
    private final int emailDailyLimit;

    public BroadcastService(Db db, PlanService plans, WalletService wallet, JobService jobs, AuditService audit, Notifier notifier, Unsubscribe unsub,
                            @Value("${app.sms.rate}") BigDecimal smsRate, @Value("${app.email.daily-limit}") int emailDailyLimit) {
        this.db = db;
        this.plans = plans;
        this.wallet = wallet;
        this.jobs = jobs;
        this.audit = audit;
        this.notifier = notifier;
        this.unsub = unsub;
        this.smsRate = smsRate;
        this.emailDailyLimit = emailDailyLimit;
    }

    public record Request(String channel, String name, String subject, String body, String audienceType, JsonNode audience) {}

    /** GSM-7 text is 160 chars (153 when concatenated); anything else (₹, Tamil...) is UCS-2: 70 (67). */
    public static int smsSegments(String text) {
        boolean unicode = text.chars().anyMatch(c -> c > 126 || c == '^' || c == '~' || c == '`' || c == '|' || c == '\\');
        int single = unicode ? 70 : 160;
        int multi = unicode ? 67 : 153;
        int len = text.length();
        return len <= single ? 1 : (int) Math.ceil(len / (double) multi);
    }

    private BigDecimal smsCostPer(String body) {
        return smsRate.multiply(BigDecimal.valueOf(smsSegments(personalise(body, "Customer Name Placeholder", "", "Placeholder City", false))));
    }

    static String personalise(String tpl, String fullName, String ignored, String city, boolean escape) {
        String first = fullName == null ? "" : fullName.trim().split("\\s+")[0];
        String f = first.length() > 20 ? first.substring(0, 20) : first;
        String n = fullName == null ? "" : fullName.length() > 30 ? fullName.substring(0, 30) : fullName;
        String c = city == null ? "" : city;
        if (escape) {
            f = Html.esc(f);
            n = Html.esc(n);
            c = Html.esc(c);
        }
        return tpl.replace("{{first_name}}", f).replace("{{name}}", n).replace("{{city}}", c);
    }

    private void feature(long ws, String channel) {
        plans.requireFeature(ws, channel.toLowerCase());
    }

    private Row load(long ws, long id) {
        return db.row("select * from broadcasts where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Broadcast"));
    }

    public List<Map<String, Object>> list(long ws, String channel) {
        return db.list("select * from broadcasts where workspace_id=:ws" + (channel != null ? " and channel=:c" : "") + " order by id desc limit 200", "ws", ws, "c", channel);
    }

    public Map<String, Object> get(long ws, long id) {
        Row b = load(ws, id);
        Map<String, Object> m = b.api();
        m.put("estimate", b.s("status").equals("DRAFT") || b.s("status").equals("SCHEDULED") ? estimate(ws, id) : null);
        return m;
    }

    private void validate(Request r) {
        List<FieldErr> errs = new ArrayList<>();
        String name = Norm.plain(r.name());
        if (name == null || name.length() < 3 || name.length() > 100) errs.add(new FieldErr("name", "bc.name", "Name must be 3 to 100 characters."));
        if (!List.of("EMAIL", "SMS", "PUSH").contains(r.channel())) errs.add(new FieldErr("channel", "bc.channel", "Choose e-mail, SMS or push."));
        String body = r.body() == null ? "" : r.body().trim();
        if ("EMAIL".equals(r.channel())) {
            String subj = Norm.plain(r.subject());
            if (subj == null || subj.length() < 3 || subj.length() > 150) errs.add(new FieldErr("subject", "bc.subject", "Subject must be 3 to 150 characters."));
            if (body.length() < 10 || body.length() > 20000) errs.add(new FieldErr("body", "bc.body", "Write the message (10 to 20,000 characters)."));
        } else if ("SMS".equals(r.channel())) {
            if (body.length() < 5 || body.length() > MAX_SMS_CHARS) errs.add(new FieldErr("body", "bc.sms", "SMS must be 5 to " + MAX_SMS_CHARS + " characters."));
        } else if ("PUSH".equals(r.channel())) {
            String t = Norm.plain(r.subject());
            if (t == null || t.length() < 2 || t.length() > 65) errs.add(new FieldErr("subject", "bc.push.title", "Title must be 2 to 65 characters."));
            if (body.length() < 3 || body.length() > 178) errs.add(new FieldErr("body", "bc.push.body", "Message must be 3 to 178 characters."));
        }
        if (!"PUSH".equals(r.channel()) && !List.of("ALL_OPTED_IN", "TAGS", "SEGMENT").contains(r.audienceType())) errs.add(new FieldErr("audienceType", "audience.type", "Choose who should receive this."));
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
    }

    private String cleanBody(Request r) {
        String b = r.body().trim();
        return "EMAIL".equals(r.channel()) ? Norm.basicHtml(b) : Norm.plain(b);
    }

    public Map<String, Object> create(long ws, long userId, Request r) {
        validate(r);
        feature(ws, r.channel());
        if (!"PUSH".equals(r.channel())) AudienceQuery.forCampaign(r.audienceType(), r.audience(), ws, id -> segmentFilter(ws, id)); // validates the audience
        long id = db.insertInto("broadcasts", "workspace_id", ws, "channel", r.channel(), "name", Norm.plain(r.name()), "subject", Norm.plain(r.subject()), "body", cleanBody(r),
                "audience_type", r.audienceType() == null ? "ALL_OPTED_IN" : r.audienceType(), "audience_json", r.audience() == null ? null : r.audience().toString(), "created_by", userId);
        audit.log("BROADCAST_CREATED", "broadcasts", id);
        return get(ws, id);
    }

    public Map<String, Object> update(long ws, long id, Request r) {
        Row b = load(ws, id);
        if (!List.of("DRAFT", "SCHEDULED").contains(b.s("status"))) throw ApiException.state("Only drafts and scheduled broadcasts can be edited.");
        validate(r);
        if (!r.channel().equals(b.s("channel"))) throw ApiException.invalid("channel", "bc.channel", "The channel cannot be changed.");
        if (!"PUSH".equals(r.channel())) AudienceQuery.forCampaign(r.audienceType(), r.audience(), ws, sid -> segmentFilter(ws, sid));
        db.update("update broadcasts set name=:n, subject=:s, body=:b, audience_type=:at, audience_json=:aj where id=:id",
                "n", Norm.plain(r.name()), "s", Norm.plain(r.subject()), "b", cleanBody(r), "at", r.audienceType() == null ? "ALL_OPTED_IN" : r.audienceType(),
                "aj", r.audience() == null ? null : r.audience().toString(), "id", id);
        return get(ws, id);
    }

    public void delete(long ws, long id) {
        Row b = load(ws, id);
        if (!List.of("DRAFT", "CANCELLED", "COMPLETED", "FAILED").contains(b.s("status"))) throw ApiException.state("Cancel the broadcast before deleting it.");
        db.update("delete from broadcasts where id=:id", "id", id);
    }

    String segmentFilter(long ws, long id) {
        return db.scalar("select filter_json from segments where id=:id and workspace_id=:ws", String.class, "id", id, "ws", ws);
    }

    private String channelCondition(String channel) {
        return "EMAIL".equals(channel) ? " and c.email is not null and c.email<>'' and c.email_unsub_at is null"
                : " and c.sms_unsub_at is null";
    }

    private AudienceQuery audience(long ws, Row b) {
        JsonNode aud = b.s("audience_json") == null ? null : Json.tree(b.s("audience_json"));
        return AudienceQuery.forCampaign(b.s("audience_type"), aud, ws, sid -> segmentFilter(ws, sid));
    }

    private Map<String, Object> params(AudienceQuery q, long ws) {
        Map<String, Object> p = new LinkedHashMap<>(q.params);
        p.put("ws", ws);
        return p;
    }

    private long countAudience(long ws, Row b) {
        if ("PUSH".equals(b.s("channel"))) return db.count("select count(*) from push_subscriptions where workspace_id=:ws and active=1", "ws", ws);
        AudienceQuery q = audience(ws, b);
        return db.rowsMap("select count(*) as n from contacts c where " + q.sql + channelCondition(b.s("channel")), params(q, ws)).get(0).l("n");
    }

    private long sentToday(long ws) {
        return db.count("select count(*) from broadcast_recipients r join broadcasts b on b.id=r.broadcast_id where b.workspace_id=:ws and b.channel='EMAIL' and r.status='SENT' and r.sent_at >= :t",
                "ws", ws, "t", Time.startOfTodayIstUtc());
    }

    public Map<String, Object> estimate(long ws, long id) {
        Row b = load(ws, id);
        long n = countAudience(ws, b);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recipients", n);
        BigDecimal cost = "SMS".equals(b.s("channel")) ? smsCostPer(b.s("body")).multiply(BigDecimal.valueOf(n)) : BigDecimal.ZERO;
        m.put("cost", cost);
        m.put("segments", "SMS".equals(b.s("channel")) ? smsSegments(personalise(b.s("body"), "Customer Name Placeholder", "", "Placeholder City", false)) : null);
        BigDecimal bal = wallet.balance(ws);
        m.put("balance", bal);
        m.put("sufficient", bal.compareTo(cost) >= 0);
        if ("EMAIL".equals(b.s("channel"))) m.put("dailyRemaining", Math.max(0, emailDailyLimit - sentToday(ws)));
        return m;
    }

    /** Starts now; reserves SMS cost up front and refunds undelivered messages when the run ends. */
    public Map<String, Object> send(long ws, long userId, long id) {
        Row b = load(ws, id);
        if (!List.of("DRAFT", "SCHEDULED").contains(b.s("status"))) throw ApiException.state("This broadcast has already been sent.");
        feature(ws, b.s("channel"));
        long total = countAudience(ws, b);
        if (total < 1) throw ApiException.invalid("audience", "bc.recipients", "No opted-in contacts match this audience.");
        if ("EMAIL".equals(b.s("channel")) && sentToday(ws) + total > emailDailyLimit)
            throw ApiException.state("Your daily e-mail limit is " + emailDailyLimit + ". Choose a smaller audience or schedule for tomorrow.");
        BigDecimal cost = "SMS".equals(b.s("channel")) ? smsCostPer(b.s("body")).multiply(BigDecimal.valueOf(total)) : BigDecimal.ZERO;
        db.tx(() -> {
            if (cost.signum() > 0) wallet.debit(ws, cost, "BROADCAST", id, "SMS broadcast " + b.s("name"), userId);
            if (!"PUSH".equals(b.s("channel"))) {
                AudienceQuery q = audience(ws, b);
                Map<String, Object> p = params(q, ws);
                p.put("bid", id);
                db.updateMap("insert ignore into broadcast_recipients (broadcast_id, contact_id) select :bid, c.id from contacts c where " + q.sql + channelCondition(b.s("channel")), p);
            }
            int n = db.update("update broadcasts set status='SENDING', started_at=:now, total_count=:t, cost=:c where id=:id and status in ('DRAFT','SCHEDULED')",
                    "now", Time.now(), "t", total, "c", cost, "id", id);
            if (n == 0) throw ApiException.state("This broadcast has already been sent.");
        });
        jobs.enqueue("BROADCAST_RUN", Map.of("broadcastId", id));
        audit.log("BROADCAST_STARTED", "broadcasts", id);
        return get(ws, id);
    }

    public Map<String, Object> schedule(long ws, long id, String when) {
        Row b = load(ws, id);
        if (!List.of("DRAFT", "SCHEDULED").contains(b.s("status"))) throw ApiException.state("This broadcast has already been sent.");
        LocalDateTime t;
        try {
            t = Time.parseUtc(when);
        } catch (Exception e) {
            throw ApiException.invalid("scheduledAt", "bc.when", "Choose a valid date and time.");
        }
        if (t.isBefore(Time.now().plusMinutes(5)) || t.isAfter(Time.now().plusDays(90))) throw ApiException.invalid("scheduledAt", "bc.when", "Choose a time at least 5 minutes from now.");
        feature(ws, b.s("channel"));
        db.update("update broadcasts set status='SCHEDULED', scheduled_at=:t where id=:id", "t", t, "id", id);
        return get(ws, id);
    }

    public Map<String, Object> cancel(long ws, long id) {
        Row b = load(ws, id);
        if ("SCHEDULED".equals(b.s("status"))) {
            db.update("update broadcasts set status='DRAFT', scheduled_at=null where id=:id", "id", id);
        } else if ("SENDING".equals(b.s("status"))) {
            db.update("update broadcasts set status='CANCELLED' where id=:id and status='SENDING'", "id", id);
            refundPending(b);
        } else {
            throw ApiException.state("Only scheduled or sending broadcasts can be cancelled.");
        }
        return get(ws, id);
    }

    /** Scheduler hook: starts broadcasts whose time has come. */
    public void startDue() {
        for (Row b : db.rows("select id, workspace_id, created_by from broadcasts where status='SCHEDULED' and scheduled_at <= :now limit 50", "now", Time.now())) {
            try {
                send(b.l("workspace_id"), b.lo("created_by") == null ? 0L : b.l("created_by"), b.l("id"));
            } catch (Exception e) {
                db.update("update broadcasts set status='FAILED' where id=:id", "id", b.l("id"));
                audit.log(b.l("workspace_id"), null, "BROADCAST_START_FAILED", "broadcasts", b.l("id"), null, Map.of("error", String.valueOf(e.getMessage())));
            }
        }
    }

    void refundPending(Row b) {
        if (!"SMS".equals(b.s("channel"))) return;
        long ws = b.l("workspace_id");
        long pending = db.count("select count(*) from broadcast_recipients where broadcast_id=:id and status='PENDING'", "id", b.l("id"));
        if (pending > 0) {
            wallet.refund(ws, smsCostPer(b.s("body")).multiply(BigDecimal.valueOf(pending)), "BROADCAST", b.l("id"), "Unsent SMS refunded");
            db.update("update broadcast_recipients set status='SKIPPED', error='Cancelled' where broadcast_id=:id and status='PENDING'", "id", b.l("id"));
        }
    }

    public Map<String, Object> testSend(long ws, long id, String to) {
        Row b = load(ws, id);
        if ("EMAIL".equals(b.s("channel"))) {
            String addr = Norm.email(to);
            if (addr == null || !addr.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw ApiException.invalid("to", "vr.email.invalid", "Enter a valid email address.");
            notifier.emailHtml(addr, "[Test] " + b.s("subject"), personalise(b.s("body"), "Test User", "", "Chennai", false));
        } else if ("SMS".equals(b.s("channel"))) {
            String phone = Phones.e164(to);
            if (phone == null) throw ApiException.invalid("to", "vr.mobile.invalid", "Enter a valid 10-digit mobile number.");
            wallet.debit(ws, smsCostPer(b.s("body")), "BROADCAST", id, "SMS test", null);
            notifier.sms(phone, personalise(b.s("body"), "Test User", "", "Chennai", false));
        } else {
            throw ApiException.badRequest("Test sends are available for e-mail and SMS.");
        }
        return Map.of("ok", true);
    }

    public Map<String, Object> recipients(long ws, long id, String status) {
        load(ws, id);
        List<Map<String, Object>> rows = db.list("select r.id, r.status, r.error, r.sent_at, c.full_name, c.email, c.phone from broadcast_recipients r join contacts c on c.id=r.contact_id " +
                "where r.broadcast_id=:id" + (status != null ? " and r.status=:s" : "") + " order by r.id limit 200", "id", id, "s", status);
        return Map.of("items", rows);
    }

    /** Opts a contact out of a channel (manual request, e.g. "stop texting me"). */
    public void optOut(long ws, long contactId, String channel, boolean out) {
        String col = "SMS".equals(channel) ? "sms_unsub_at" : "email_unsub_at";
        int n = db.update("update contacts set " + col + "=" + (out ? ":now" : "null") + " where id=:id and workspace_id=:ws and deleted_at is null", "now", Time.now(), "id", contactId, "ws", ws);
        if (n == 0) throw ApiException.notFound("Contact");
    }

    // ------------------------------------------------------------------ used by the runner
    String unsubscribeLink(long ws, long contactId, long bid, String channel) {
        return unsub.link(ws, contactId, bid, channel);
    }

    BigDecimal smsCost(String body) {
        return smsCostPer(body);
    }
}
