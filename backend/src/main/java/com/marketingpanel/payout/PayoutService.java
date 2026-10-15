package com.marketingpanel.payout;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.auth.OtpService;
import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import com.marketingpanel.jobs.JobHandler;
import com.marketingpanel.jobs.JobService;
import com.marketingpanel.payment.GatewayRegistry;
import com.marketingpanel.payment.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Paying a promoter (SRS Figure 4.3): the money leaves the promoter's balance BEFORE the transfer, so it can never be paid twice.
 * payout_ref is our idempotency key for RupiyaX; a failed or reversed transfer returns the money with a PAYOUT_REVERSAL row.
 */
@Service
public class PayoutService implements JobHandler {
    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);
    private final Db db;
    private final PromoterService promoters;
    private final EarningsLedger ledger;
    private final OtpService otp;
    private final JobService jobs;
    private final GatewayRegistry gateways;
    private final Crypto crypto;
    private final Settings settings;
    private final LedgerGuard guard;
    private final AuditService audit;
    private final Notifier notifier;

    public PayoutService(Db db, PromoterService promoters, EarningsLedger ledger, OtpService otp, JobService jobs, GatewayRegistry gateways, Crypto crypto,
                         Settings settings, LedgerGuard guard, AuditService audit, Notifier notifier) {
        this.db = db;
        this.promoters = promoters;
        this.ledger = ledger;
        this.otp = otp;
        this.jobs = jobs;
        this.gateways = gateways;
        this.crypto = crypto;
        this.settings = settings;
        this.guard = guard;
        this.audit = audit;
        this.notifier = notifier;
    }

    public record WithdrawRequest(BigDecimal amount, Long methodId, String otp) {}

    public Map<String, Object> withdraw(long userId, WithdrawRequest r) {
        Row p = promoters.byUser(userId);
        if (!"ACTIVE".equals(p.s("status"))) throw ApiException.forbidden("Withdrawals are on hold for your account. Contact support.");
        if (!"VERIFIED".equals(p.s("kyc_status"))) throw new ApiException(ErrorCode.KYC_REQUIRED, "Complete KYC before withdrawing.");
        if (guard.blocked()) throw ApiException.state("Payouts are paused for a routine accounting check. Please try again shortly.");
        BigDecimal amount = r.amount() == null ? BigDecimal.ZERO : r.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(settings.payoutMin) < 0 || amount.compareTo(settings.payoutMax) > 0)
            throw new ApiException(ErrorCode.PAYOUT_LIMIT, "Withdrawals are ₹" + settings.payoutMin.intValue() + " to ₹" + String.format("%,d", settings.payoutMax.intValue()) + " per request.");
        if (amount.compareTo(p.d("balance_available")) > 0)
            throw ApiException.insufficient("You can withdraw ₹100 up to your available balance (" + Money.format(p.d("balance_available")) + ").");
        Row m = db.row("select * from payout_methods where id=:id and promoter_id=:p and deleted_at is null", "id", r.methodId(), "p", p.l("id"))
                .orElseThrow(() -> ApiException.invalid("methodId", "withdraw.method", "Choose a verified payout method."));
        if (!m.b("is_verified")) throw ApiException.invalid("methodId", "withdraw.method", "Choose a verified payout method.");
        if (m.t("cooldown_until") != null && m.t("cooldown_until").isAfter(Time.now()))
            throw new ApiException(ErrorCode.PAYOUT_LIMIT, "This payout method is still under the 24-hour safety hold.");
        Row today = db.row("select count(*) as n, coalesce(sum(amount),0) as total from payouts where promoter_id=:p and requested_at >= :d and status not in ('REJECTED','FAILED','REVERSED')",
                "p", p.l("id"), "d", Time.startOfTodayIstUtc()).orElseThrow();
        if (today.l("n") >= settings.payoutMaxPerDay) throw new ApiException(ErrorCode.PAYOUT_LIMIT, "You can make at most " + settings.payoutMaxPerDay + " withdrawal requests per day.");
        if (today.d("total").add(amount).compareTo(settings.payoutMax) > 0) throw new ApiException(ErrorCode.PAYOUT_LIMIT, "Daily withdrawal limit is ₹" + String.format("%,d", settings.payoutMax.intValue()) + ".");
        String phone = db.scalar("select phone from users where id=:id", String.class, "id", userId);
        otp.verify(phone, "WITHDRAW", r.otp());

        BigDecimal tds = amount.multiply(settings.tdsPercent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal net = amount.subtract(tds);
        boolean auto = p.i("risk_score") < settings.autoApproveRisk && amount.compareTo(settings.autoApproveAmount) <= 0;
        String ref = "PO" + Ids.ulid();
        long id = db.tx(() -> {
            long pid = db.insertInto("payouts", "promoter_id", p.l("id"), "payout_method_id", m.l("id"), "payout_ref", ref, "amount", amount, "tds_amount", tds, "net_amount", net,
                    "mode", "UPI".equals(m.s("type")) ? "UPI" : "IMPS", "status", auto ? "APPROVED" : "REQUESTED");
            ledger.post(p.l("id"), "PAYOUT", amount.negate(), "AVAILABLE", null, pid, "Withdrawal " + ref); // money leaves the balance first
            return pid;
        });
        if (auto) jobs.enqueue("PAYOUT_SEND", Map.of("payoutId", id), Time.now(), 2, 6);
        audit.log(null, userId, "WITHDRAWAL_REQUESTED", "payouts", id, null, Map.of("amount", amount.toString(), "auto", auto));
        return payout(p.l("id"), id);
    }

    public Map<String, Object> payout(long promoterId, long id) {
        return db.one("select id, payout_ref, amount, tds_amount, fee, net_amount, mode, status, utr, failure_reason, requested_at, processed_at from payouts where id=:id and promoter_id=:p", "id", id, "p", promoterId)
                .orElseThrow(() -> ApiException.notFound("Payout"));
    }

    public List<Map<String, Object>> list(long userId) {
        return db.list("select po.id, po.payout_ref, po.amount, po.tds_amount, po.net_amount, po.mode, po.status, po.utr, po.failure_reason, po.requested_at, po.processed_at, pm.type as method_type, pm.account_last4 " +
                "from payouts po join payout_methods pm on pm.id=po.payout_method_id where po.promoter_id=:p order by po.id desc limit 100", "p", promoters.byUser(userId).l("id"));
    }

    // ------------------------------------------------------ sending (job)

    @Override
    public String type() {
        return "PAYOUT_SEND";
    }

    @Override
    public void handle(JsonNode p) {
        long id = p.get("payoutId").asLong();
        Row po = db.row("select * from payouts where id=:id", "id", id).orElse(null);
        if (po == null || !List.of("APPROVED", "PROCESSING").contains(po.s("status"))) return;
        Row m = db.row("select * from payout_methods where id=:id", "id", po.l("payout_method_id")).orElseThrow();
        PaymentGateway.PayoutMethod pm = new PaymentGateway.PayoutMethod(m.s("type"), m.bytes("upi_id_enc") == null ? null : crypto.decrypt(m.bytes("upi_id_enc")),
                m.bytes("account_number_enc") == null ? null : crypto.decrypt(m.bytes("account_number_enc")), m.s("ifsc"), m.s("holder_name"), po.s("mode"));
        PaymentGateway.PayoutResult res = gateways.get(po.s("gateway")).createPayout(po.s("payout_ref"), po.d("net_amount"), pm); // idempotent on payout_ref
        db.update("update payouts set status='PROCESSING', gateway_payout_id=:g where id=:id and status in ('APPROVED','PROCESSING')", "g", res.gatewayPayoutId(), "id", id);
    }

    @Override
    public void onDead(JsonNode p, String error) {
        long id = p.get("payoutId").asLong();
        Row po = db.row("select * from payouts where id=:id", "id", id).orElse(null);
        if (po == null) return;
        PaymentGateway.PayoutStatus st = gateways.get(po.s("gateway")).fetchPayoutStatus(po.s("payout_ref"));
        if ("NOT_FOUND".equals(st.status())) fail(po, "FAILED", "The transfer could not be created: " + error);
        else onGateway(new Events.PayoutEvent(po.s("payout_ref"), st.status(), st.utr(), st.reason()));
        log.error("Payout {} send job is DEAD: {}", id, error);
    }

    // ----------------------------------------------------- gateway events

    @EventListener
    public void onGateway(Events.PayoutEvent e) {
        Row po = db.row("select * from payouts where payout_ref=:r", "r", e.payoutRef()).orElse(null);
        if (po == null) return;
        switch (e.status()) {
            case "SUCCESS" -> {
                int n = db.update("update payouts set status='SUCCESS', utr=:u, processed_at=:now, failure_reason=null where id=:id and status in ('APPROVED','PROCESSING')", "u", e.utr(), "now", Time.now(), "id", po.l("id"));
                if (n > 0) {
                    markConversionsPaid(po.l("promoter_id"), po.d("amount"));
                    String phone = db.scalar("select u.phone from promoters p join users u on u.id=p.user_id where p.id=:p", String.class, "p", po.l("promoter_id"));
                    notifier.sms(phone, "Marketing Panel: your payout of " + Money.format(po.d("net_amount")) + " was paid. UTR " + e.utr());
                    audit.log(null, null, "PAYOUT_SUCCESS", "payouts", po.l("id"), null, Map.of("utr", String.valueOf(e.utr())));
                }
            }
            case "FAILED", "REVERSED" -> fail(po, e.status(), e.reason());
            default -> {}
        }
    }

    private void fail(Row po, String status, String reason) {
        db.tx(() -> {
            int n = db.update("update payouts set status=:s, failure_reason=:r, processed_at=:now where id=:id and status in ('REQUESTED','APPROVED','PROCESSING')",
                    "s", status, "r", reason == null ? null : (reason.length() > 255 ? reason.substring(0, 255) : reason), "now", Time.now(), "id", po.l("id"));
            if (n > 0) ledger.post(po.l("promoter_id"), "PAYOUT_REVERSAL", po.d("amount"), "AVAILABLE", null, po.l("id"), "Payout " + status.toLowerCase() + ": money returned");
        });
        String phone = db.scalar("select u.phone from promoters p join users u on u.id=p.user_id where p.id=:p", String.class, "p", po.l("promoter_id"));
        notifier.sms(phone, "Marketing Panel: your payout of " + Money.format(po.d("amount")) + " could not be completed. The amount is back in your balance.");
    }

    private void markConversionsPaid(long promoterId, BigDecimal paid) {
        BigDecimal left = paid;
        for (Row c : db.rows("select id, promoter_payout from conversions where promoter_id=:p and status='APPROVED' order by approved_at, id limit 2000", "p", promoterId)) {
            if (left.compareTo(c.d("promoter_payout")) < 0) break;
            db.update("update conversions set status='PAID' where id=:id and status='APPROVED'", "id", c.l("id"));
            left = left.subtract(c.d("promoter_payout"));
        }
    }

    /** Safety net for missed webhooks: poll payouts stuck in PROCESSING for over 15 minutes. */
    public int pollProcessing() {
        int n = 0;
        for (Row po : db.rows("select * from payouts where status='PROCESSING' and requested_at < :t limit 100", "t", Time.now().minusMinutes(15))) {
            try {
                PaymentGateway.PayoutStatus st = gateways.get(po.s("gateway")).fetchPayoutStatus(po.s("payout_ref"));
                if (List.of("SUCCESS", "FAILED", "REVERSED").contains(st.status())) {
                    onGateway(new Events.PayoutEvent(po.s("payout_ref"), st.status(), st.utr(), st.reason()));
                    n++;
                }
            } catch (Exception ignored) {
                // next run
            }
        }
        return n;
    }
}
