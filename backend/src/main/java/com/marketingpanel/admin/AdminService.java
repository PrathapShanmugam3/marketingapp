package com.marketingpanel.admin;

import com.marketingpanel.auth.AuthUser;
import com.marketingpanel.auth.JwtAuthFilter;
import com.marketingpanel.common.*;
import com.marketingpanel.whatsapp.RateService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** P25 Super admin console: tenants, plans, message rates, wallet adjustments, platform stats. Every action is audited. */
@Service
public class AdminService {
    private final Db db;
    private final WalletService wallet;
    private final AuditService audit;
    private final JwtAuthFilter jwtFilter;
    private final RateService rates;
    private final NotificationService notifications;

    public AdminService(Db db, WalletService wallet, AuditService audit, JwtAuthFilter jwtFilter, RateService rates, NotificationService notifications) {
        this.db = db;
        this.wallet = wallet;
        this.audit = audit;
        this.jwtFilter = jwtFilter;
        this.rates = rates;
        this.notifications = notifications;
    }

    public Map<String, Object> workspaces(String q, String status, Long cursor) {
        StringBuilder sb = new StringBuilder("select w.id, w.name, w.phone, w.email, w.city, w.state, w.status, w.wallet_balance, w.trial_ends_at, w.created_at, w.suspend_reason, p.code as plan_code, " +
                "(select u.email from workspace_members m join users u on u.id=m.user_id where m.workspace_id=w.id and m.role in ('OWNER','AGENCY_ADMIN') order by m.id limit 1) as owner_email, " +
                "(select count(*) from contacts c where c.workspace_id=w.id and c.deleted_at is null) as contacts from workspaces w join plans p on p.id=w.plan_id where w.deleted_at is null");
        List<Object> kv = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            sb.append(" and (w.name like :q or w.phone like :q or w.email like :q or w.id=:id)");
            kv.add("q");
            kv.add("%" + q.trim() + "%");
            kv.add("id");
            kv.add(q.trim().matches("^\\d{1,18}$") ? Long.parseLong(q.trim()) : -1L);
        }
        if (status != null && !status.isBlank()) {
            sb.append(" and w.status=:s");
            kv.add("s");
            kv.add(status);
        }
        if (cursor != null) {
            sb.append(" and w.id < :c");
            kv.add("c");
            kv.add(cursor);
        }
        sb.append(" order by w.id desc limit 51");
        List<Row> rows = db.rows(sb.toString(), kv.toArray());
        boolean more = rows.size() > 50;
        if (more) rows = rows.subList(0, 50);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", Row.api(rows));
        out.put("nextCursor", more ? rows.get(rows.size() - 1).l("id") : null);
        return out;
    }

    public Map<String, Object> setStatus(AuthUser me, long id, String status, String reason) {
        Row w = db.row("select * from workspaces where id=:id and deleted_at is null", "id", id).orElseThrow(() -> ApiException.notFound("Workspace"));
        if (!List.of("TRIAL", "ACTIVE", "PAST_DUE", "SUSPENDED", "CANCELLED").contains(status)) throw ApiException.badRequest("Unknown status.");
        String r = Norm.plain(reason);
        if ("SUSPENDED".equals(status) && (r == null || r.length() < 10 || r.length() > 255)) throw ApiException.invalid("reason", "admin.reason", "Give a reason for suspending.");
        db.update("update workspaces set status=:s, suspend_reason=:r where id=:id", "s", status, "r", "SUSPENDED".equals(status) ? r : null, "id", id);
        jwtFilter.evict(id);
        if ("SUSPENDED".equals(status)) notifications.notifyOwners(id, "SUSPENDED", "Your workspace was suspended", r, "/support");
        audit.log(id, me.userId(), "WORKSPACE_STATUS_" + status, "workspaces", id, Map.of("status", w.s("status")), Map.of("status", status, "reason", String.valueOf(r)));
        return db.one("select id, name, status, suspend_reason from workspaces where id=:id", "id", id).orElseThrow();
    }

    public Map<String, Object> adjustWallet(AuthUser me, long id, BigDecimal amount, String note) {
        db.row("select id from workspaces where id=:id", "id", id).orElseThrow(() -> ApiException.notFound("Workspace"));
        String n = Norm.plain(note);
        if (amount == null || amount.signum() == 0 || amount.abs().compareTo(new BigDecimal("10000000")) > 0) throw ApiException.invalid("amount", "admin.amount", "Enter a non-zero amount.");
        if (n == null || n.length() < 10 || n.length() > 255) throw ApiException.invalid("note", "admin.note", "Add a note for this adjustment (10 to 255 characters).");
        BigDecimal after = wallet.adjust(id, amount, n, me.userId());
        audit.log(id, me.userId(), "WALLET_ADJUSTMENT", "workspaces", id, null, Map.of("amount", amount.toString(), "note", n));
        return Map.of("balance", after);
    }

    public record RateRequest(String category, String countryCode, BigDecimal metaRate, BigDecimal sellRate, String effectiveFrom) {}

    public List<Map<String, Object>> rates() {
        return db.list("select * from message_rates order by effective_from desc, category, country_code limit 200");
    }

    public Map<String, Object> addRate(AuthUser me, RateRequest r) {
        List<FieldErr> errs = new ArrayList<>();
        if (!List.of("MARKETING", "UTILITY", "AUTHENTICATION").contains(r.category())) errs.add(new FieldErr("category", "rate.category", "Choose a category."));
        String cc = r.countryCode() == null || r.countryCode().isBlank() ? "91" : r.countryCode().trim();
        if (!cc.matches("^\\d{1,5}$")) errs.add(new FieldErr("countryCode", "rate.country", "Enter a calling code like 91."));
        BigDecimal min = new BigDecimal("0.0001"), max = new BigDecimal("99.9999");
        if (r.sellRate() == null || r.sellRate().compareTo(min) < 0 || r.sellRate().compareTo(max) > 0) errs.add(new FieldErr("sellRate", "rate.sell", "Rate must be greater than 0."));
        if (r.metaRate() == null || r.metaRate().compareTo(min) < 0 || r.metaRate().compareTo(max) > 0) errs.add(new FieldErr("metaRate", "rate.meta", "Rate must be greater than 0."));
        LocalDate from;
        try {
            from = r.effectiveFrom() == null ? LocalDate.now() : LocalDate.parse(r.effectiveFrom());
        } catch (Exception e) {
            from = null;
            errs.add(new FieldErr("effectiveFrom", "rate.date", "Choose a date."));
        }
        if (from != null && from.isBefore(LocalDate.now(Time.IST))) errs.add(new FieldErr("effectiveFrom", "rate.date", "The effective date must be today or later."));
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        if (db.exists("select 1 from message_rates where category=:c and country_code=:cc and effective_from=:d", "c", r.category(), "cc", cc, "d", from))
            throw ApiException.duplicate("effectiveFrom", "rate.exists", "A rate already exists for this date.");
        long id = db.insertInto("message_rates", "category", r.category(), "country_code", cc, "meta_rate", r.metaRate(), "sell_rate", r.sellRate(), "effective_from", from);
        rates.evict();
        audit.log(null, me.userId(), "MESSAGE_RATE_ADDED", "message_rates", id, null, Map.of("category", r.category(), "sell", r.sellRate().toString(), "from", from.toString()));
        return db.one("select * from message_rates where id=:id", "id", id).orElseThrow();
    }

    public List<Map<String, Object>> plans() {
        return db.list("select * from plans order by price_monthly");
    }

    public record PlanRequest(BigDecimal priceMonthly, BigDecimal priceYearly, Integer maxUsers, Integer maxContacts, Integer maxWorkspaces, Integer maxDomains, Map<String, Object> features, Boolean active) {}

    public Map<String, Object> updatePlan(AuthUser me, long id, PlanRequest r) {
        Row p = db.row("select * from plans where id=:id", "id", id).orElseThrow(() -> ApiException.notFound("Plan"));
        Map<String, Object> cols = new LinkedHashMap<>();
        if (r.priceMonthly() != null) cols.put("price_monthly", check(r.priceMonthly()));
        if (r.priceYearly() != null) cols.put("price_yearly", check(r.priceYearly()));
        if (r.maxUsers() != null) cols.put("max_users", pos(r.maxUsers()));
        if (r.maxContacts() != null) cols.put("max_contacts", pos(r.maxContacts()));
        if (r.maxWorkspaces() != null) cols.put("max_workspaces", pos(r.maxWorkspaces()));
        if (r.maxDomains() != null) cols.put("max_domains", Math.max(0, r.maxDomains()));
        if (r.features() != null) cols.put("features", Json.write(r.features()));
        if (r.active() != null) cols.put("is_active", r.active());
        db.updateTable("plans", "id", id, cols);
        audit.log(null, me.userId(), "PLAN_UPDATED", "plans", id, p.api(), cols);
        return db.one("select * from plans where id=:id", "id", id).orElseThrow();
    }

    private static BigDecimal check(BigDecimal v) {
        if (v.signum() < 0 || v.compareTo(new BigDecimal("9999999")) > 0) throw ApiException.invalid("price", "plan.price", "Enter a valid amount.");
        return v;
    }

    private static int pos(int v) {
        if (v < 1) throw ApiException.invalid("limit", "plan.limit", "Limits must be at least 1.");
        return v;
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workspacesByStatus", db.list("select status, count(*) as n from workspaces where deleted_at is null group by status"));
        out.put("mrr", db.scalar("select coalesce(sum(case when s.billing_cycle='YEARLY' then p.price_yearly/12 else p.price_monthly end),0) from subscriptions s join plans p on p.id=s.plan_id where s.status='ACTIVE'", BigDecimal.class));
        out.put("walletBalanceTotal", db.scalar("select coalesce(sum(wallet_balance),0) from workspaces", BigDecimal.class));
        out.put("messagesToday", db.count("select coalesce(sum(sent_count),0) from campaigns where started_at >= :t", "t", Time.startOfTodayIstUtc()));
        out.put("payoutsPending", db.scalar("select coalesce(sum(net_amount),0) from payouts where status in ('REQUESTED','APPROVED','PROCESSING')", BigDecimal.class));
        out.put("payoutsRequested", db.count("select count(*) from payouts where status='REQUESTED'"));
        out.put("conversionsToday", db.count("select count(*) from conversions where created_at >= :t", "t", Time.startOfTodayIstUtc()));
        out.put("openTickets", db.count("select count(*) from support_tickets where status in ('OPEN','WAITING_CUSTOMER')"));
        out.put("jobsDead", db.count("select count(*) from jobs where status='DEAD'"));
        out.put("jobsPending", db.count("select count(*) from jobs where status='PENDING'"));
        out.put("ledgerBlocked", "true".equals(db.scalar("select value from system_flags where name='LEDGER_BLOCK'", String.class)));
        out.put("promoters", db.count("select count(*) from promoters"));
        return out;
    }

    public List<Map<String, Object>> auditLogs(Long workspaceId, String action, Long cursor) {
        StringBuilder sb = new StringBuilder("select a.*, u.email as user_email from audit_logs a left join users u on u.id=a.user_id where 1=1");
        List<Object> kv = new ArrayList<>();
        if (workspaceId != null) {
            sb.append(" and a.workspace_id=:ws");
            kv.add("ws");
            kv.add(workspaceId);
        }
        if (action != null && !action.isBlank()) {
            sb.append(" and a.action like :a");
            kv.add("a");
            kv.add(action.trim() + "%");
        }
        if (cursor != null) {
            sb.append(" and a.id < :c");
            kv.add("c");
            kv.add(cursor);
        }
        sb.append(" order by a.id desc limit 100");
        return db.list(sb.toString(), kv.toArray());
    }

    public List<Map<String, Object>> deadJobs() {
        return db.list("select id, type, last_error, attempts, updated_at from jobs where status='DEAD' order by id desc limit 100");
    }

    public void retryJob(long id) {
        int n = db.update("update jobs set status='PENDING', attempts=0, run_at=:now where id=:id and status='DEAD'", "now", Time.now(), "id", id);
        if (n == 0) throw ApiException.notFound("Job");
    }
}
