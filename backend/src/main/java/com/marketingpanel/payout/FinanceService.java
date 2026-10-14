package com.marketingpanel.payout;

import com.marketingpanel.auth.AuthUser;
import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import com.marketingpanel.jobs.JobService;
import com.marketingpanel.payment.GatewayRegistry;
import com.marketingpanel.payment.PaymentGateway;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** P38 Finance console: payout approvals (two-person rule for big batches), promoter holds, reconciliation. */
@Service
public class FinanceService {
    private final Db db;
    private final GatewayRegistry gateways;
    private final EarningsLedger ledger;
    private final JobService jobs;
    private final Settings settings;
    private final AuditService audit;
    private final Notifier notifier;
    private final PayoutService payouts;

    public FinanceService(Db db, GatewayRegistry gateways, EarningsLedger ledger, JobService jobs, Settings settings, AuditService audit, Notifier notifier, PayoutService payouts) {
        this.db = db;
        this.gateways = gateways;
        this.ledger = ledger;
        this.jobs = jobs;
        this.settings = settings;
        this.audit = audit;
        this.notifier = notifier;
        this.payouts = payouts;
    }

    public record BatchRequest(List<Long> payoutIds, String action, String reason) {}

    public List<Map<String, Object>> queue(String status) {
        String st = status == null || status.isBlank() ? "REQUESTED" : status;
        return db.list("select po.id, po.payout_ref, po.amount, po.net_amount, po.tds_amount, po.mode, po.status, po.requested_at, po.first_approver_id, po.failure_reason, po.utr, " +
                "p.id as promoter_id, p.referral_code, p.kyc_status, p.risk_score, p.status as promoter_status, u.full_name, pm.type as method_type, pm.account_last4, " +
                "(select count(*) from payout_methods x where x.deleted_at is null and x.ifsc is not null and x.account_last4=pm.account_last4 and x.ifsc=pm.ifsc and x.promoter_id<>p.id) as shared_account " +
                "from payouts po join promoters p on p.id=po.promoter_id join users u on u.id=p.user_id join payout_methods pm on pm.id=po.payout_method_id " +
                "where po.status=:s order by po.id limit 500", "s", st);
    }

    public Map<String, Object> batch(AuthUser me, BatchRequest r) {
        List<Long> ids = r.payoutIds() == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(r.payoutIds()));
        if (ids.isEmpty() || ids.size() > 200) throw ApiException.invalid("payoutIds", "batch.size", "Select up to 200 payouts.");
        List<Row> rows = db.rows("select po.*, p.kyc_status, p.status as promoter_status from payouts po join promoters p on p.id=po.promoter_id where po.id in (:ids)", "ids", ids);
        if (rows.size() != ids.size()) throw ApiException.notFound("Payout");
        for (Row po : rows) if (!"REQUESTED".equals(po.s("status"))) throw ApiException.state("Payout " + po.l("id") + " is not waiting for approval.");
        if ("REJECT".equals(r.action())) {
            String reason = Norm.plain(r.reason());
            if (reason == null || reason.length() < 10 || reason.length() > 255) throw ApiException.invalid("reason", "batch.reason", "Give a reason (10 to 255 characters).");
            for (Row po : rows) reject(me, po, reason);
            return Map.of("rejected", rows.size());
        }
        if (!"APPROVE".equals(r.action())) throw ApiException.badRequest("Unknown action.");
        for (Row po : rows) {
            if (!"VERIFIED".equals(po.s("kyc_status")) || "BLOCKED".equals(po.s("promoter_status")))
                throw ApiException.state("Payout " + po.l("id") + " cannot be approved: KYC is not verified or the promoter is blocked.");
        }
        BigDecimal total = rows.stream().map(x -> x.d("net_amount")).reduce(BigDecimal.ZERO, BigDecimal::add);
        PaymentGateway gw = gateways.primary();
        if (gw.payoutAccountBalance().compareTo(total) < 0)
            throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE, "The platform payout account at the gateway cannot cover this batch. Top it up first.", List.of(), Map.of("topUpAlert", true), null);
        boolean big = total.compareTo(settings.batchSecondApproval) > 0;
        if (big) { // two-person rule: a different Finance user must give the second approval
            boolean secondDone = rows.stream().allMatch(x -> x.lo("first_approver_id") != null && x.l("first_approver_id") != me.userId());
            boolean mine = rows.stream().anyMatch(x -> x.lo("first_approver_id") != null && x.l("first_approver_id") == me.userId());
            if (!secondDone) {
                if (mine) throw ApiException.state("A second Finance user must approve this batch (total " + Money.format(total) + ").");
                for (Row po : rows) db.update("update payouts set first_approver_id=:u where id=:id and first_approver_id is null", "u", me.userId(), "id", po.l("id"));
                audit.log(null, me.userId(), "PAYOUT_FIRST_APPROVAL", "payouts", null, null, Map.of("ids", ids, "total", total.toString()));
                return Map.of("approved", 0, "awaitingSecondApproval", rows.size(), "total", total);
            }
        }
        for (Row po : rows) {
            int n = db.update("update payouts set status='APPROVED', approved_by=:u where id=:id and status='REQUESTED'", "u", me.userId(), "id", po.l("id"));
            if (n > 0) jobs.enqueue("PAYOUT_SEND", Map.of("payoutId", po.l("id")), Time.now(), 2, 6);
        }
        audit.log(null, me.userId(), "PAYOUT_BATCH_APPROVED", "payouts", null, null, Map.of("ids", ids, "total", total.toString()));
        return Map.of("approved", rows.size(), "total", total);
    }

    private void reject(AuthUser me, Row po, String reason) {
        db.tx(() -> {
            int n = db.update("update payouts set status='REJECTED', reject_reason=:r, failure_reason=:r, approved_by=:u, processed_at=:now where id=:id and status='REQUESTED'",
                    "r", reason, "u", me.userId(), "now", Time.now(), "id", po.l("id"));
            if (n > 0) ledger.post(po.l("promoter_id"), "PAYOUT_REVERSAL", po.d("amount"), "AVAILABLE", null, po.l("id"), "Payout rejected: money returned");
        });
        String phone = db.scalar("select u.phone from promoters p join users u on u.id=p.user_id where p.id=:p", String.class, "p", po.l("promoter_id"));
        notifier.sms(phone, "Marketing Panel: your withdrawal was not approved. " + reason);
        audit.log(null, me.userId(), "PAYOUT_REJECTED", "payouts", po.l("id"), null, Map.of("reason", reason));
    }

    public List<Map<String, Object>> promoters(String q) {
        return db.list("select p.id, p.referral_code, p.kyc_status, p.pan_last4, p.legal_name, p.balance_pending, p.balance_available, p.lifetime_earned, p.risk_score, p.status, u.full_name, u.phone, u.email, p.created_at " +
                "from promoters p join users u on u.id=p.user_id where (:q is null or u.full_name like :lq or u.phone like :lq or p.referral_code=:q) order by p.id desc limit 200",
                "q", q == null || q.isBlank() ? null : q.trim(), "lq", "%" + (q == null ? "" : q.trim()) + "%");
    }

    public Map<String, Object> setPromoterStatus(AuthUser me, long id, String status, String reason) {
        if (!List.of("ACTIVE", "ON_HOLD", "BLOCKED").contains(status)) throw ApiException.badRequest("Unknown status.");
        String r = Norm.plain(reason);
        if (r == null || r.length() < 10 || r.length() > 255) throw ApiException.invalid("reason", "promoter.reason", "Give a reason (10 to 255 characters).");
        int n = db.update("update promoters set status=:s where id=:id", "s", status, "id", id);
        if (n == 0) throw ApiException.notFound("Promoter");
        audit.log(null, me.userId(), "PROMOTER_STATUS_" + status, "promoters", id, null, Map.of("reason", r));
        return db.one("select id, status, risk_score from promoters where id=:id", "id", id).orElseThrow();
    }

    /** Conversions flagged by fraud rules and still inside their hold. */
    public List<Map<String, Object>> fraud() {
        return db.list("select v.id, v.offer_id, v.promoter_id, v.fraud_flags, v.seconds_to_convert, v.promoter_payout, v.created_at, v.hold_until, o.name as offer_name, p.referral_code " +
                "from conversions v join offers o on o.id=v.offer_id left join promoters p on p.id=v.promoter_id where v.status='PENDING' and v.fraud_flags is not null order by v.id desc limit 200");
    }

    // ------------------------------------------------------- reconciliation

    /** Compares our successful payments and payouts with the gateway's settlement lines for one day. */
    public Map<String, Object> reconcile(LocalDate day, Long adminId) {
        PaymentGateway gw = gateways.primary();
        List<PaymentGateway.SettlementLine> lines = gw.settlements(day);
        Map<String, BigDecimal> theirs = new HashMap<>();
        for (var l : lines) theirs.put(l.ref(), l.amount());
        int matched = 0, mismatch = 0;
        List<String> problems = new ArrayList<>();
        var from = Time.startOfIstDayUtc(day);
        var to = Time.startOfIstDayUtc(day.plusDays(1));
        BigDecimal gross = BigDecimal.ZERO;
        for (Row p : db.rows("select order_ref, amount from payments where status='SUCCESS' and paid_at >= :f and paid_at < :t", "f", from, "t", to)) {
            BigDecimal t = theirs.remove(p.s("order_ref"));
            gross = gross.add(p.d("amount"));
            if (t != null && t.compareTo(p.d("amount")) == 0) matched++;
            else {
                mismatch++;
                problems.add("payment " + p.s("order_ref") + " ours " + p.d("amount") + " gateway " + t);
            }
        }
        for (Row p : db.rows("select payout_ref, net_amount from payouts where status='SUCCESS' and processed_at >= :f and processed_at < :t", "f", from, "t", to)) {
            BigDecimal t = theirs.remove(p.s("payout_ref"));
            if (t != null && t.compareTo(p.d("net_amount")) == 0) matched++;
            else {
                mismatch++;
                problems.add("payout " + p.s("payout_ref") + " ours " + p.d("net_amount") + " gateway " + t);
            }
        }
        for (var e : theirs.entrySet()) { // lines only the gateway has
            if (db.exists("select 1 from payments where order_ref=:r", "r", e.getKey()) || db.exists("select 1 from payouts where payout_ref=:r", "r", e.getKey())) continue;
            if (e.getKey().startsWith("MP") || e.getKey().startsWith("PO")) {
                mismatch++;
                problems.add("gateway line " + e.getKey() + " " + e.getValue() + " is unknown to us");
            }
        }
        String ref = "REC-" + day + "-" + System.currentTimeMillis() % 100000;
        db.insertInto("settlements", "gateway", "RUPIYAX", "settlement_ref", ref, "settled_on", day, "gross_amount", gross, "fees", BigDecimal.ZERO, "tax_on_fees", BigDecimal.ZERO,
                "net_amount", gross, "matched_payments", matched, "mismatch_count", mismatch, "status", mismatch == 0 ? "MATCHED" : "MISMATCH");
        if (mismatch > 0) {
            Long uid = adminId != null ? adminId : db.scalar("select id from users where admin_role in ('FINANCE','SUPER_ADMIN') order by id limit 1", Long.class);
            if (uid != null) {
                long t = db.insertInto("support_tickets", "user_id", uid, "category", "BILLING", "subject", "Reconciliation mismatch for " + day, "priority", "HIGH", "reference_type", "SETTLEMENT");
                db.insertInto("ticket_messages", "ticket_id", t, "sender_id", uid, "body", String.join("\n", problems.subList(0, Math.min(50, problems.size()))), "is_internal", true);
            }
            audit.log(null, adminId, "RECONCILIATION_MISMATCH", "settlements", null, null, problems);
        }
        return Map.of("day", day.toString(), "matched", matched, "mismatches", mismatch, "problems", problems);
    }

    /** Manual import of the RupiyaX settlement CSV (columns: ref,amount,kind). */
    public Map<String, Object> importSettlement(byte[] csv, Long adminId) {
        if (csv.length == 0 || csv.length > 10 << 20) throw ApiException.invalid("file", "settle.size", "Upload the settlement CSV (max 10 MB).");
        String[] lines = new String(csv, java.nio.charset.StandardCharsets.UTF_8).split("\\R");
        int ok = 0, bad = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 1; i < lines.length; i++) {
            String[] c = lines[i].split(",");
            try {
                BigDecimal a = new BigDecimal(c[1].trim());
                String ref = c[0].trim();
                boolean known = db.exists("select 1 from payments where order_ref=:r and amount=:a", "r", ref, "a", a) || db.exists("select 1 from payouts where payout_ref=:r and net_amount=:a", "r", ref, "a", a);
                if (known) ok++;
                else bad++;
                total = total.add(a);
            } catch (Exception e) {
                bad++;
            }
        }
        db.insertInto("settlements", "gateway", "RUPIYAX", "settlement_ref", "CSV-" + System.currentTimeMillis(), "settled_on", LocalDate.now(Time.IST), "gross_amount", total, "fees", BigDecimal.ZERO,
                "tax_on_fees", BigDecimal.ZERO, "net_amount", total, "matched_payments", ok, "mismatch_count", bad, "status", bad == 0 ? "MATCHED" : "MISMATCH");
        audit.log(null, adminId, "SETTLEMENT_IMPORTED", "settlements", null, null, Map.of("matched", ok, "mismatch", bad));
        return Map.of("matched", ok, "mismatches", bad);
    }

    public List<Map<String, Object>> settlements() {
        return db.list("select * from settlements order by id desc limit 100");
    }

    public List<Map<String, Object>> heldPayments() {
        return db.list("select id, workspace_id, order_ref, amount, purpose, status, failure_reason, created_at from payments where failure_reason like 'MISMATCH%' order by id desc limit 100");
    }
}
