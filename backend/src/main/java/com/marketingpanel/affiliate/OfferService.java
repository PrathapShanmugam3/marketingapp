package com.marketingpanel.affiliate;

import com.marketingpanel.auth.IpMatcher;
import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/** P32 Offer setup & postback (advertiser). Needs the Growth or Agency plan (app_promotion). */
@Service
public class OfferService {
    private static final Pattern EVENT = Pattern.compile("^[a-z0-9_]{2,50}$");
    private final Db db;
    private final PlanService plans;
    private final Crypto crypto;
    private final Settings settings;
    private final WalletService wallet;
    private final AuditService audit;
    private final PostbackService postbacks;
    private final NotificationService notifications;

    public OfferService(Db db, PlanService plans, Crypto crypto, Settings settings, WalletService wallet, AuditService audit, PostbackService postbacks, NotificationService notifications) {
        this.db = db;
        this.plans = plans;
        this.crypto = crypto;
        this.settings = settings;
        this.wallet = wallet;
        this.audit = audit;
        this.postbacks = postbacks;
        this.notifications = notifications;
    }

    public record OfferRequest(String name, Long appId, String landingUrl, String eventType, String eventName, BigDecimal advertiserPrice, BigDecimal promoterPayout,
                               Integer dailyCap, Integer totalCap, Integer perPromoterDailyCap, Integer attributionWindowHours, Integer holdDays, Integer minCtitSeconds,
                               Boolean incentiveAllowed, List<String> allowedIps, String startsAt, String endsAt, String category) {}

    public List<Map<String, Object>> list(long ws) {
        return db.list("select o.id, o.name, o.app_id, o.event_type, o.event_name, o.advertiser_price, o.promoter_payout, o.daily_cap, o.total_cap, o.status, o.pause_reason, o.tested_at, o.starts_at, o.ends_at, o.category, " +
                "(select count(*) from tracking_clicks c where c.offer_id=o.id) as clicks, " +
                "(select count(*) from conversions v where v.offer_id=o.id and v.status in ('PENDING','APPROVED','PAID')) as conversions " +
                "from offers o where o.workspace_id=:ws order by o.id desc", "ws", ws);
    }

    public Map<String, Object> get(long ws, long id) {
        Map<String, Object> o = db.one("select id, workspace_id, app_id, name, event_type, event_name, landing_url, advertiser_price, promoter_payout, daily_cap, total_cap, attribution_window_hours, " +
                "min_ctit_seconds, incentive_allowed, per_promoter_daily_cap, hold_days, allowed_ips, status, pause_reason, tested_at, starts_at, ends_at, category, created_at from offers where id=:id and workspace_id=:ws",
                "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Offer"));
        String pb = "https://" + settings.trackDomain + "/pb?offer=" + id + "&click_id={click_id}&txn_id={txn_id}&event={event}&amount={amount}&sig={hmac}";
        o.put("postbackUrl", pb);
        o.put("templates", Map.of(
                "appsflyer", "https://" + settings.trackDomain + "/pb?offer=" + id + "&click_id={click_id}&txn_id={af_event_id}&event={event_name}&amount={event_revenue}&sig={hmac}",
                "adjust", "https://" + settings.trackDomain + "/pb?offer=" + id + "&click_id={click_id}&txn_id={event_id}&event={activity_kind}&amount={revenue}&sig={hmac}",
                "branch", "https://" + settings.trackDomain + "/pb?offer=" + id + "&click_id={click_id}&txn_id={transaction_id}&event={name}&amount={revenue}&sig={hmac}"));
        o.put("signatureRule", "sig = HMAC-SHA256(secret, click_id + '|' + txn_id + '|' + event), lower-case hex");
        return o;
    }

    private Map<String, Object> clean(long ws, OfferRequest r) {
        List<FieldErr> errs = new ArrayList<>();
        String name = Norm.plain(Norm.name(r.name()));
        if (name == null || name.length() < 3 || name.length() > 100) errs.add(new FieldErr("name", "offer.name", "Enter an offer name."));
        Row app = null;
        if (r.appId() != null) {
            app = db.row("select * from apps where id=:id and workspace_id=:ws", "id", r.appId(), "ws", ws).orElse(null);
            if (app == null) errs.add(new FieldErr("appId", "offer.app", "Choose one of your apps."));
        }
        String url = Norm.text(r.landingUrl());
        if (url == null && app != null) url = app.s("web_fallback_url") + (app.s("web_fallback_url").contains("?") ? "&" : "?") + "click_id={click_id}";
        if (url == null || !UrlGuard.isPublicHttps(url.replace("{click_id}", "x").replace("{sub1}", "x").replace("{sub2}", "x"), 2000) || !url.contains("{click_id}"))
            errs.add(new FieldErr("landingUrl", "offer.url", "The link must include {click_id}."));
        String event = r.eventType() == null ? "" : r.eventType();
        if (!List.of("INSTALL", "REGISTRATION", "PURCHASE", "LEAD", "CUSTOM").contains(event)) errs.add(new FieldErr("eventType", "offer.event", "Choose what counts as a conversion."));
        if ("CUSTOM".equals(event) && (r.eventName() == null || !EVENT.matcher(r.eventName()).matches())) errs.add(new FieldErr("eventName", "offer.eventname", "Use lowercase letters, numbers and _ (2 to 50 characters)."));
        BigDecimal price = r.advertiserPrice(), payout = r.promoterPayout();
        if (price == null || price.compareTo(BigDecimal.ONE) < 0 || price.compareTo(new BigDecimal("10000")) > 0) errs.add(new FieldErr("advertiserPrice", "offer.price", "Enter a price between ₹1 and ₹10,000."));
        else if (payout == null || payout.signum() <= 0 || payout.compareTo(price.multiply(BigDecimal.valueOf(100 - settings.offerMinMarginPercent)).divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN)) > 0)
            errs.add(new FieldErr("promoterPayout", "offer.payout", "Promoter payout is above the allowed maximum."));
        Integer daily = r.dailyCap(), total = r.totalCap(), perPromoter = r.perPromoterDailyCap();
        if (daily != null && (daily < 1 || daily > 100_000)) errs.add(new FieldErr("dailyCap", "offer.cap.daily", "Daily cap must be 1 to 1,00,000."));
        if (total != null && daily != null && total < daily) errs.add(new FieldErr("totalCap", "offer.cap.total", "Total cap must be at least the daily cap."));
        if (perPromoter != null && (perPromoter < 1 || (daily != null && perPromoter > daily))) errs.add(new FieldErr("perPromoterDailyCap", "offer.cap.promoter", "Per-promoter cap must be at most the daily cap."));
        int window = r.attributionWindowHours() == null ? 168 : r.attributionWindowHours();
        if (window < 1 || window > 720) errs.add(new FieldErr("attributionWindowHours", "offer.window", "Window must be 1 to 720 hours."));
        int hold = r.holdDays() == null ? 7 : r.holdDays();
        if (hold < 0 || hold > 60) errs.add(new FieldErr("holdDays", "offer.hold", "Hold must be 0 to 60 days."));
        int ctit = r.minCtitSeconds() == null ? 10 : r.minCtitSeconds();
        if (ctit < 0 || ctit > 600) errs.add(new FieldErr("minCtitSeconds", "offer.ctit", "Enter 0 to 600 seconds."));
        List<String> ips = r.allowedIps() == null ? List.of() : r.allowedIps().stream().map(String::trim).filter(x -> !x.isEmpty()).toList();
        if (ips.size() > 20 || ips.stream().anyMatch(i -> !IpMatcher.valid(i))) errs.add(new FieldErr("allowedIps", "offer.ips", "Enter valid IP addresses (up to 20)."));
        LocalDateTime starts = null, ends = null;
        try {
            if (r.startsAt() != null && !r.startsAt().isBlank()) starts = Time.parseUtc(r.startsAt());
            if (r.endsAt() != null && !r.endsAt().isBlank()) ends = Time.parseUtc(r.endsAt());
        } catch (Exception e) {
            errs.add(new FieldErr("startsAt", "offer.dates", "End must be after start."));
        }
        if (ends != null && (starts != null ? !ends.isAfter(starts) : !ends.isAfter(Time.now()))) errs.add(new FieldErr("endsAt", "offer.dates", "End must be after start."));
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("app_id", r.appId());
        m.put("event_type", event);
        m.put("event_name", "CUSTOM".equals(event) ? r.eventName() : null);
        m.put("landing_url", url);
        m.put("advertiser_price", price);
        m.put("promoter_payout", payout);
        m.put("daily_cap", daily);
        m.put("total_cap", total);
        m.put("per_promoter_daily_cap", perPromoter);
        m.put("attribution_window_hours", window);
        m.put("hold_days", hold);
        m.put("min_ctit_seconds", ctit);
        m.put("incentive_allowed", Boolean.TRUE.equals(r.incentiveAllowed()));
        m.put("allowed_ips", ips.isEmpty() ? null : Json.write(ips));
        m.put("starts_at", starts);
        m.put("ends_at", ends);
        m.put("category", Norm.text(r.category()) == null ? null : Norm.plain(r.category()));
        return m;
    }

    public Map<String, Object> create(long ws, long userId, OfferRequest r) {
        plans.requireFeature(ws, "app_promotion");
        Map<String, Object> c = clean(ws, r);
        String secret = Ids.randomHex(32);
        List<Object> kv = new ArrayList<>(List.of("workspace_id", ws, "created_by", userId, "postback_secret_enc", crypto.encrypt(secret), "status", "DRAFT"));
        c.forEach((k, v) -> {
            kv.add(k);
            kv.add(v);
        });
        long id = db.insertInto("offers", kv.toArray());
        audit.log("OFFER_CREATED", "offers", id);
        Map<String, Object> out = get(ws, id);
        out.put("postbackSecret", secret); // shown once
        return out;
    }

    public Map<String, Object> update(long ws, long id, OfferRequest r) {
        Row o = load(ws, id);
        if ("ENDED".equals(o.s("status"))) throw ApiException.state("An ended offer cannot be edited.");
        Map<String, Object> c = clean(ws, r);
        boolean material = o.d("advertiser_price").compareTo((BigDecimal) c.get("advertiser_price")) != 0 || !o.s("landing_url").equals(c.get("landing_url"));
        db.updateTable("offers", "id", id, c, "workspace_id", ws);
        if (material && "ACTIVE".equals(o.s("status"))) db.update("update offers set tested_at=null, status='PAUSED', pause_reason='RETEST' where id=:id", "id", id);
        audit.log("OFFER_UPDATED", "offers", id);
        return get(ws, id);
    }

    Row load(long ws, long id) {
        return db.row("select * from offers where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Offer"));
    }

    public Map<String, Object> setStatus(long ws, long id, String status) {
        Row o = load(ws, id);
        if (!List.of("ACTIVE", "PAUSED", "ENDED").contains(status)) throw ApiException.badRequest("Unknown status.");
        if ("ENDED".equals(o.s("status"))) throw ApiException.state("This offer has ended.");
        if ("ACTIVE".equals(status)) {
            plans.requireFeature(ws, "app_promotion");
            if (o.t("tested_at") == null) throw ApiException.state("Run the test click and test postback before activating.");
            BigDecimal conv = BigDecimal.valueOf(o.lo("daily_cap") == null ? 50 : Math.min(50, o.l("daily_cap")));
            BigDecimal need = o.d("advertiser_price").multiply(conv);
            if (wallet.balance(ws).compareTo(need) < 0)
                throw ApiException.insufficient("Add at least " + Money.format(need) + " to your wallet to activate this offer (covers " + conv.intValue() + " conversions).");
        }
        db.update("update offers set status=:s, pause_reason=null where id=:id", "s", status, "id", id);
        audit.log("OFFER_" + status, "offers", id);
        return get(ws, id);
    }

    /** Rotates the HMAC secret; the old one keeps working for 24 hours. */
    public Map<String, Object> rotateSecret(long ws, long id) {
        Row o = load(ws, id);
        String secret = Ids.randomHex(32);
        db.update("update offers set postback_secret_prev_enc=postback_secret_enc, postback_secret_prev_until=:u, postback_secret_enc=:n where id=:id",
                "u", Time.now().plusHours(24), "n", crypto.encrypt(secret), "id", id);
        audit.log("OFFER_SECRET_ROTATED", "offers", id);
        return Map.of("postbackSecret", secret, "oldSecretValidUntil", Time.now().plusHours(24).toString() + "Z");
    }

    /** Test click + test postback. A successful test unlocks activation. The test click is removed afterwards. */
    public Map<String, Object> test(long ws, long id) {
        Row o = load(ws, id);
        String clickId = Ids.ulid();
        db.insertInto("tracking_clicks", "click_id", clickId, "offer_id", id, "sub1", "test", "platform", "OTHER", "ip_hash", Hashing.sha256("test-" + id), "user_agent", "mp-test");
        String secret = crypto.decrypt(o.bytes("postback_secret_enc"));
        String txn = "test-" + Ids.randomCode(8);
        String sig = Hashing.hmacSha256(secret, clickId + "|" + txn + "|" + "test");
        PostbackService.Result res = postbacks.process(Map.of("offer", String.valueOf(id), "click_id", clickId, "txn_id", txn, "event", "test", "sig", sig), "127.0.0.1", true);
        db.update("delete from tracking_clicks where click_id=:c", "c", clickId);
        if ("ACCEPTED".equals(res.code())) db.update("update offers set tested_at=:now where id=:id", "now", Time.now(), "id", id);
        return Map.of("result", res.code(), "passed", "ACCEPTED".equals(res.code()), "sampleClickId", clickId, "sampleSignature", sig);
    }

    /** Offers pause themselves when the advertiser wallet cannot cover one more conversion. */
    public int autoPause() {
        int n = 0;
        for (Row o : db.rows("select o.id, o.workspace_id, o.name from offers o join workspaces w on w.id=o.workspace_id where o.status='ACTIVE' and w.wallet_balance < o.advertiser_price")) {
            db.update("update offers set status='PAUSED', pause_reason='WALLET_LOW' where id=:id and status='ACTIVE'", "id", o.l("id"));
            notifications.notifyOwners(o.l("workspace_id"), "OFFER_PAUSED", "Offer " + o.s("name") + " paused", "Your wallet can no longer cover one more conversion. Top up to resume.", "/growth/offers");
            n++;
        }
        for (Row o : db.rows("select id from offers where status in ('ACTIVE','PAUSED') and ends_at is not null and ends_at < :now", "now", Time.now()))
            n += db.update("update offers set status='ENDED' where id=:id", "id", o.l("id"));
        return n;
    }
}
