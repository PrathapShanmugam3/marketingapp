package com.marketingpanel.affiliate;

import com.marketingpanel.auth.IpMatcher;
import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import com.marketingpanel.jobs.JobService;
import com.marketingpanel.payout.EarningsLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Postback tracking (SRS 3.5). Checks run in this order: offer, source IP, signature, click, window, dedupe, caps, fraud.
 * Every call is logged to postback_logs and the endpoint always answers 200 so MMPs do not retry valid-but-rejected calls.
 */
@Service
public class PostbackService {
    private static final Logger log = LoggerFactory.getLogger(PostbackService.class);
    private final Db db;
    private final Crypto crypto;
    private final WalletService wallet;
    private final EarningsLedger ledger;
    private final JobService jobs;
    private final NotificationService notifications;
    private final Settings settings;

    public PostbackService(Db db, Crypto crypto, WalletService wallet, EarningsLedger ledger, JobService jobs, NotificationService notifications, Settings settings) {
        this.db = db;
        this.crypto = crypto;
        this.wallet = wallet;
        this.ledger = ledger;
        this.jobs = jobs;
        this.notifications = notifications;
        this.settings = settings;
    }

    public record Result(String code, Long conversionId) {}

    public Result process(Map<String, String> p, String ip, boolean dryRun) {
        Long offerId = parseLong(p.get("offer"));
        Result r;
        try {
            r = run(p, ip, dryRun, offerId);
        } catch (Exception e) {
            log.error("Postback processing failed", e);
            r = new Result("OFFER_INACTIVE", null);
        }
        if (!dryRun) logIn(offerId, r, p, ip);
        return r;
    }

    private Result run(Map<String, String> p, String ip, boolean dryRun, Long offerId) {
        Row offer = offerId == null ? null : db.row("select * from offers where id=:id", "id", offerId).orElse(null);
        if (offer == null || (!dryRun && !"ACTIVE".equals(offer.s("status")))) return new Result("OFFER_INACTIVE", null);
        if (!dryRun && offer.t("ends_at") != null && offer.t("ends_at").isBefore(Time.now())) return new Result("OFFER_INACTIVE", null);
        if (!dryRun && offer.s("allowed_ips") != null) {
            boolean ok = false;
            for (Object e : Json.list(offer.s("allowed_ips"))) if (IpMatcher.matches(String.valueOf(e), ip)) ok = true;
            if (!ok) return new Result("IP_BLOCKED", null);
        }
        String clickId = Norm.text(p.get("click_id")), txn = Norm.text(p.get("txn_id")), event = Norm.text(p.get("event")), sig = Norm.text(p.get("sig"));
        if (clickId == null || txn == null || event == null || sig == null || txn.length() > 100 || event.length() > 50) return new Result("INVALID_SIGNATURE", null);
        String data = clickId + "|" + txn + "|" + event;
        boolean sigOk = Hashing.constantEquals(Hashing.hmacSha256(crypto.decrypt(offer.bytes("postback_secret_enc")), data), sig.toLowerCase());
        if (!sigOk && offer.bytes("postback_secret_prev_enc") != null && offer.t("postback_secret_prev_until") != null && offer.t("postback_secret_prev_until").isAfter(Time.now()))
            sigOk = Hashing.constantEquals(Hashing.hmacSha256(crypto.decrypt(offer.bytes("postback_secret_prev_enc")), data), sig.toLowerCase());
        if (!sigOk) return new Result("INVALID_SIGNATURE", null);
        Row click = db.row("select * from tracking_clicks where click_id=:c and offer_id=:o", "c", clickId, "o", offer.l("id")).orElse(null);
        if (click == null || click.b("is_bot")) return new Result("UNKNOWN_CLICK", null);
        LocalDateTime now = Time.now();
        long seconds = Math.max(0, Duration.between(click.t("clicked_at"), now).toSeconds());
        if (seconds > offer.l("attribution_window_hours") * 3600L) return new Result("WINDOW_EXPIRED", null);
        if (db.exists("select 1 from conversions where offer_id=:o and (external_txn_id=:t or (click_id=:c and event_type=:e and status<>'REJECTED')) limit 1", "o", offer.l("id"), "t", txn, "c", clickId, "e", clip(event, 30)))
            return new Result("DUPLICATE", null);
        LocalDateTime dayStart = Time.startOfTodayIstUtc();
        long ws = offer.l("workspace_id");
        if (offer.lo("daily_cap") != null && db.count("select count(*) from conversions where offer_id=:o and created_at >= :d and status<>'REJECTED'", "o", offer.l("id"), "d", dayStart) >= offer.l("daily_cap"))
            return new Result("CAP_REACHED", null);
        if (offer.lo("total_cap") != null && db.count("select count(*) from conversions where offer_id=:o and status in ('PENDING','APPROVED','PAID')", "o", offer.l("id")) >= offer.l("total_cap"))
            return new Result("CAP_REACHED", null);
        Long promoterId = click.lo("promoter_id");
        if (promoterId != null && offer.lo("per_promoter_daily_cap") != null &&
                db.count("select count(*) from conversions where offer_id=:o and promoter_id=:p and created_at >= :d and status<>'REJECTED'", "o", offer.l("id"), "p", promoterId, "d", dayStart) >= offer.l("per_promoter_daily_cap"))
            return new Result("CAP_REACHED", null);
        if (wallet.balance(ws).compareTo(offer.d("advertiser_price")) < 0) {
            db.update("update offers set status='PAUSED', pause_reason='WALLET_LOW' where id=:id and status='ACTIVE'", "id", offer.l("id"));
            notifications.notifyOwners(ws, "OFFER_PAUSED", "Offer " + offer.s("name") + " paused", "Your wallet can no longer cover one more conversion. Top up to resume.", "/growth/offers");
            return new Result("CAP_REACHED", null);
        }
        if (dryRun) return new Result("ACCEPTED", null);

        List<String> flags = new ArrayList<>();
        if (seconds < offer.l("min_ctit_seconds")) flags.add("LOW_CTIT");
        if (db.count("select count(*) from conversions v join tracking_clicks c on c.click_id=v.click_id where v.offer_id=:o and c.ip_hash=:h and v.created_at >= :d and v.status<>'REJECTED'",
                "o", offer.l("id"), "h", click.s("ip_hash"), "d", dayStart) >= 3) flags.add("SAME_IP");
        if (click.b("is_vpn")) flags.add("VPN");
        if (promoterId != null) {
            long clicks = db.count("select count(*) from tracking_clicks where promoter_id=:p and offer_id=:o and clicked_at > :t and is_bot=0", "p", promoterId, "o", offer.l("id"), "t", now.minusDays(7));
            long convs = db.count("select count(*) from conversions where promoter_id=:p and offer_id=:o and created_at > :t", "p", promoterId, "o", offer.l("id"), "t", now.minusDays(7));
            if (clicks >= 20 && (convs + 1) * 10 > clicks * 3) flags.add("HIGH_CVR");
        }
        int hold = offer.i("hold_days");
        if (!flags.isEmpty()) hold = Math.max(1, hold * 2); // accepted but flagged: extended hold
        BigDecimal sale = null;
        try {
            if (p.get("amount") != null && !p.get("amount").isBlank()) sale = new BigDecimal(p.get("amount").trim()).max(BigDecimal.ZERO).min(new BigDecimal("99999999.99"));
        } catch (NumberFormatException ignored) {
            // amount is optional information
        }
        final BigDecimal saleAmount = sale;
        final int holdDays = hold;
        try {
            long convId = db.tx(() -> {
                long id = db.insertInto("conversions", "offer_id", offer.l("id"), "click_id", clickId, "promoter_id", promoterId, "event_type", clip(event, 30),
                        "external_txn_id", txn, "sale_amount", saleAmount, "advertiser_price", offer.d("advertiser_price"), "promoter_payout", offer.d("promoter_payout"),
                        "status", "PENDING", "fraud_flags", flags.isEmpty() ? null : Json.write(flags), "seconds_to_convert", seconds, "hold_until", now.plusDays(holdDays));
                wallet.debit(ws, offer.d("advertiser_price"), "CONVERSION", id, "Conversion on offer " + offer.s("name"), null);
                if (promoterId != null) ledger.post(promoterId, "EARNING", offer.d("promoter_payout"), "PENDING", id, null, "Conversion on " + offer.s("name"));
                return id;
            });
            if (promoterId != null) jobs.enqueue("POSTBACK_OUT", Map.of("conversionId", convId), now, 5, 4);
            return new Result("ACCEPTED", convId);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return new Result("DUPLICATE", null);
        } catch (ApiException e) {
            if (e.code() == ErrorCode.INSUFFICIENT_BALANCE) return new Result("CAP_REACHED", null);
            throw e;
        }
    }

    private static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    private static Long parseLong(String s) {
        try {
            return s == null ? null : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void logIn(Long offerId, Result r, Map<String, String> p, String ip) {
        Map<String, String> safe = new LinkedHashMap<>(p);
        if (safe.containsKey("sig")) safe.put("sig", "***");
        StringBuilder url = new StringBuilder("/pb?");
        safe.forEach((k, v) -> url.append(k).append('=').append(v == null ? "" : (v.length() > 100 ? v.substring(0, 100) : v)).append('&'));
        String u = url.toString();
        db.insertInto("postback_logs", "direction", "IN", "offer_id", offerId != null && db.exists("select 1 from offers where id=:id", "id", offerId) ? offerId : null,
                "conversion_id", r.conversionId(), "url", u.length() > 2000 ? u.substring(0, 2000) : u, "params", Json.write(safe), "source_ip", ip, "result", r.code());
    }
}
