package com.marketingpanel.billing;

import com.marketingpanel.common.*;
import com.marketingpanel.common.validation.GstinValidator;
import com.marketingpanel.config.Settings;
import com.marketingpanel.payment.PaymentService;
import com.marketingpanel.workspace.States;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/** P22 Billing, plans and wallet. */
@Service
public class BillingService {
    private final Db db;
    private final PaymentService payments;
    private final WalletService wallet;
    private final PlanService plans;
    private final Settings settings;
    private final AuditService audit;

    public BillingService(Db db, PaymentService payments, WalletService wallet, PlanService plans, Settings settings, AuditService audit) {
        this.db = db;
        this.payments = payments;
        this.wallet = wallet;
        this.plans = plans;
        this.settings = settings;
        this.audit = audit;
    }

    public Map<String, Object> overview(long ws) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> w = db.one("select id, name, status, trial_ends_at, wallet_balance, state, gstin from workspaces where id=:id", "id", ws).orElseThrow();
        out.put("workspace", w);
        out.put("plan", plans.plan(ws).api());
        out.put("subscription", db.one("select s.*, p.code as plan_code, p.name as plan_name from subscriptions s join plans p on p.id=s.plan_id where s.workspace_id=:ws order by s.id desc limit 1", "ws", ws).orElse(null));
        out.put("plans", db.list("select id, code, name, price_monthly, price_yearly, max_users, max_contacts, max_workspaces, max_domains, features from plans where is_active=1 order by price_monthly"));
        out.put("invoices", db.list("select id, invoice_number, type, taxable_amount, cgst, sgst, igst, total, status, issued_at from invoices where workspace_id=:ws order by id desc limit 50", "ws", ws));
        out.put("walletTransactions", db.list("select id, type, amount, balance_after, reference_type, note, created_at from wallet_transactions where workspace_id=:ws order by id desc limit 30", "ws", ws));
        out.put("rates", db.list("select category, country_code, sell_rate from message_rates where effective_from <= curdate() and country_code='91' order by category, effective_from desc"));
        out.put("usage", usage(ws));
        return out;
    }

    public Map<String, Object> usage(long ws) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("contacts", db.count("select count(*) from contacts where workspace_id=:ws and deleted_at is null", "ws", ws));
        u.put("users", db.count("select count(*) from workspace_members where workspace_id=:ws and invite_status in ('ACCEPTED','PENDING')", "ws", ws));
        u.put("domains", db.count("select count(*) from custom_domains where workspace_id=:ws", "ws", ws));
        u.put("clientWorkspaces", db.count("select count(*) from workspaces where parent_workspace_id=:ws and deleted_at is null", "ws", ws));
        return u;
    }

    public record TopUp(BigDecimal amount, String gstin) {}

    public record Subscribe(String planCode, String cycle, String gstin) {}

    private String checkGstin(long ws, String gstin) {
        if (gstin == null || gstin.isBlank()) return null;
        String g = gstin.trim().toUpperCase();
        if (!GstinValidator.P.matcher(g).matches()) throw ApiException.invalid("gstin", "vr.gstin.invalid", "Enter a valid 15-character GSTIN.");
        String state = db.scalar("select state from workspaces where id=:id", String.class, "id", ws);
        if (!States.gstinMatches(g, state)) throw ApiException.invalid("gstin", "gstin.state", "GSTIN does not match the selected state.");
        return g;
    }

    public Map<String, Object> topUp(long ws, TopUp r) {
        BigDecimal a = r.amount();
        if (a == null || a.compareTo(new BigDecimal("500")) < 0 || a.compareTo(new BigDecimal("50000")) > 0 || a.remainder(new BigDecimal("100")).signum() != 0)
            throw ApiException.invalid("amount", "topup.amount", "Top-up must be ₹500 to ₹50,000 in multiples of ₹100.");
        String gstin = checkGstin(ws, r.gstin());
        Map<String, Object> o = payments.createOrder(ws, "WALLET", a.setScale(2), null, null, gstin);
        audit.log("WALLET_TOPUP_STARTED", "payments", ((Number) o.get("paymentId")).longValue());
        return o;
    }

    public Map<String, Object> subscribe(long ws, Subscribe r) {
        Row plan = db.row("select * from plans where code=:c and is_active=1", "c", r.planCode()).orElseThrow(() -> ApiException.invalid("planCode", "plan.invalid", "Choose a plan."));
        if (!List.of("MONTHLY", "YEARLY").contains(r.cycle())) throw ApiException.invalid("cycle", "plan.cycle", "Choose monthly or yearly.");
        String gstin = checkGstin(ws, r.gstin());
        Row cur = plans.plan(ws);
        if (plan.d("price_monthly").compareTo(cur.d("price_monthly")) < 0) { // downgrade: usage must fit the lower plan
            Map<String, Object> u = usage(ws);
            List<String> over = new ArrayList<>();
            if (((Number) u.get("contacts")).longValue() > plan.l("max_contacts")) over.add("contacts: " + u.get("contacts") + " of " + plan.l("max_contacts") + " allowed");
            if (((Number) u.get("users")).longValue() > plan.l("max_users")) over.add("team members: " + u.get("users") + " of " + plan.l("max_users") + " allowed");
            if (((Number) u.get("domains")).longValue() > plan.l("max_domains")) over.add("domains: " + u.get("domains") + " of " + plan.l("max_domains") + " allowed");
            if (((Number) u.get("clientWorkspaces")).longValue() > plan.l("max_workspaces") - ("AGENCY".equals(cur.s("code")) ? 0 : 1) && ((Number) u.get("clientWorkspaces")).longValue() > 0 && plan.l("max_workspaces") <= 1)
                over.add("client workspaces: " + u.get("clientWorkspaces") + " not allowed on this plan");
            if (!over.isEmpty()) throw new ApiException(ErrorCode.INVALID_STATE, "Reduce your usage before downgrading: " + String.join("; ", over) + ".",
                    List.of(), Map.of("reduce", over), null);
        }
        BigDecimal price = "YEARLY".equals(r.cycle()) ? plan.d("price_yearly") : plan.d("price_monthly");
        Map<String, Object> o = payments.createOrder(ws, "SUBSCRIPTION", price, plan.l("id"), r.cycle(), gstin);
        audit.log("SUBSCRIBE_STARTED", "payments", ((Number) o.get("paymentId")).longValue());
        return o;
    }

    public void cancelAtPeriodEnd(long ws) {
        int n = db.update("update subscriptions set cancel_at_period_end=1 where workspace_id=:ws and status='ACTIVE'", "ws", ws);
        if (n == 0) throw ApiException.state("There is no active subscription to cancel.");
        audit.log("SUBSCRIPTION_CANCEL_AT_END", "subscriptions", null);
    }

    /** Daily: renewal reminders with a pay link, then HALTED / PAST_DUE after the period ends (SRS P22). */
    public int renewals() {
        int n = 0;
        for (Row s : db.rows("select * from subscriptions where status='ACTIVE' and current_period_end is not null and current_period_end < :t", "t", Time.now().plusDays(3))) {
            long ws = s.l("workspace_id");
            if (s.t("current_period_end").isBefore(Time.now())) {
                if (s.b("cancel_at_period_end")) {
                    db.update("update subscriptions set status='CANCELLED' where id=:id", "id", s.l("id"));
                    db.update("update workspaces set status='CANCELLED' where id=:ws and status='ACTIVE'", "ws", ws);
                } else {
                    db.update("update subscriptions set status='HALTED' where id=:id", "id", s.l("id"));
                    db.update("update workspaces set status='PAST_DUE' where id=:ws and status='ACTIVE'", "ws", ws);
                    db.insert("insert into notifications (workspace_id,type,title,body,link) values (:ws,'PAST_DUE','Payment overdue','Renew your plan within 7 days to keep sending messages.','/settings/billing')", "ws", ws);
                }
                n++;
            } else if (!s.b("cancel_at_period_end") && !db.exists("select 1 from notifications where workspace_id=:ws and type='RENEWAL_DUE' and created_at > :t", "ws", ws, "t", Time.now().minusDays(4))) {
                Row plan = db.row("select * from plans where id=:id", "id", s.l("plan_id")).orElseThrow();
                BigDecimal price = "YEARLY".equals(s.s("billing_cycle")) ? plan.d("price_yearly") : plan.d("price_monthly");
                try {
                    Map<String, Object> o = payments.createOrder(ws, "SUBSCRIPTION", price, plan.l("id"), s.s("billing_cycle"), null);
                    db.insert("insert into notifications (workspace_id,type,title,body,link) values (:ws,'RENEWAL_DUE','Your plan renews soon',:b,'/settings/billing')", "ws", ws,
                            "b", "Pay " + Money.format((BigDecimal) o.get("total")) + " to renew: " + o.get("payUrl"));
                    n++;
                } catch (ApiException ignored) {
                    // gateway down: the reminder is retried on the next daily run
                }
            }
        }
        return n;
    }
}
