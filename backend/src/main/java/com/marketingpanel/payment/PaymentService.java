package com.marketingpanel.payment;

import com.marketingpanel.billing.Gst;
import com.marketingpanel.billing.InvoiceService;
import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Money in (SRS 4.3). A payment becomes SUCCESS only when the signed webhook AND a server-side status check agree, and the
 * amount, currency and order_ref match our row exactly. Mismatches are logged and the payment is held for Finance.
 */
@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private final Db db;
    private final GatewayRegistry gateways;
    private final WalletService wallet;
    private final InvoiceService invoices;
    private final Settings settings;
    private final AuditService audit;
    private final NotificationService notifications;

    public PaymentService(Db db, GatewayRegistry gateways, WalletService wallet, InvoiceService invoices, Settings settings, AuditService audit, NotificationService notifications) {
        this.db = db;
        this.gateways = gateways;
        this.wallet = wallet;
        this.invoices = invoices;
        this.settings = settings;
        this.audit = audit;
        this.notifications = notifications;
    }

    /** Creates a payment row and a gateway order. taxable = amount before GST. */
    public Map<String, Object> createOrder(long ws, String purpose, BigDecimal taxable, Long planId, String cycle, String gstin) {
        Row w = db.row("select w.*, u.email as owner_email, u.full_name as owner_name from workspaces w left join workspace_members m on m.workspace_id=w.id and m.role in ('OWNER','AGENCY_ADMIN') " +
                "left join users u on u.id=m.user_id where w.id=:id limit 1", "id", ws).orElseThrow(() -> ApiException.notFound("Workspace"));
        Gst gst = Gst.of(taxable, w.s("state"), settings.companyState);
        String orderRef = "MP" + Ids.ulid();
        long id = db.insertInto("payments", "workspace_id", ws, "gateway", "RUPIYAX", "order_ref", orderRef, "purpose", purpose, "amount", gst.total(), "status", "CREATED",
                "plan_id", planId, "billing_cycle", cycle, "taxable_amount", gst.taxable(), "customer_gstin", gstin);
        try {
            PaymentGateway.GatewayOrder o = gateways.primary().createOrder(orderRef, gst.total(), purpose,
                    new PaymentGateway.Customer(w.s("owner_name") == null ? w.s("name") : w.s("owner_name"), w.s("owner_email") == null ? w.s("email") : w.s("owner_email"), w.s("phone")));
            db.update("update payments set status='PENDING', pay_url=:u where id=:id", "u", o.payUrl(), "id", id);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("paymentId", id);
            out.put("orderRef", orderRef);
            out.put("payUrl", o.payUrl());
            out.put("taxable", gst.taxable());
            out.put("gst", gst.tax());
            out.put("total", gst.total());
            return out;
        } catch (Exception e) {
            db.update("update payments set status='FAILED', failure_reason=:r where id=:id", "r", "Gateway order failed", "id", id);
            log.warn("createOrder failed: {}", e.toString());
            throw ApiException.gateway("The payment gateway is not reachable right now. Please try again.");
        }
    }

    /** Called from the webhook job (or the status poll): confirms with a server-side status check, then finalises once. */
    public void settle(String orderRef) {
        Row p = db.row("select * from payments where order_ref=:r", "r", orderRef).orElse(null);
        if (p == null || "SUCCESS".equals(p.s("status")) || "REFUNDED".equals(p.s("status"))) return;
        PaymentGateway.PaymentStatus st = gateways.get(p.s("gateway")).fetchPaymentStatus(orderRef);
        if ("FAILED".equals(st.status())) {
            db.update("update payments set status='FAILED', failure_reason='Payment failed at the gateway' where id=:id and status in ('CREATED','PENDING')", "id", p.l("id"));
            return;
        }
        if (!"SUCCESS".equals(st.status())) throw new IllegalStateException("Payment not confirmed by the gateway yet: " + st.status()); // retried by the job queue
        boolean match = orderRef.equals(st.orderRef()) && "INR".equals(st.currency()) && st.amount() != null && st.amount().compareTo(p.d("amount")) == 0;
        if (!match) {
            String why = "MISMATCH: expected " + p.d("amount") + " INR, gateway says " + st.amount() + " " + st.currency();
            db.update("update payments set failure_reason=:r where id=:id", "r", why, "id", p.l("id"));
            audit.log(p.l("workspace_id"), null, "PAYMENT_MISMATCH", "payments", p.l("id"), null, Map.of("detail", why));
            log.error("Payment {} held: {}", orderRef, why);
            return;
        }
        db.tx(() -> {
            int n = db.update("update payments set status='SUCCESS', gateway_txn_id=:t, utr=:u, method=:m, paid_at=:now, failure_reason=null where id=:id and status in ('CREATED','PENDING')",
                    "t", st.txnId(), "u", st.utr(), "m", st.method(), "now", Time.now(), "id", p.l("id"));
            if (n == 0) return; // another worker finalised it
            finalizePayment(p);
        });
    }

    private void finalizePayment(Row p) {
        long ws = p.l("workspace_id");
        Row w = db.row("select * from workspaces where id=:id for update", "id", ws).orElseThrow();
        BigDecimal taxable = p.d("taxable_amount");
        Gst gst = Gst.of(taxable, w.s("state"), settings.companyState);
        String type = p.s("purpose");
        long invoiceId = invoices.create(ws, type, gst, p.s("customer_gstin"), w.s("state"));
        db.update("update payments set invoice_id=:i where id=:id", "i", invoiceId, "id", p.l("id"));
        switch (type) {
            case "WALLET" -> wallet.credit(ws, taxable, "PAYMENT", p.l("id"), "Wallet top-up", null);
            case "SUBSCRIPTION" -> activateSubscription(p, w);
            default -> {}
        }
        notifications.notifyOwners(ws, "PAYMENT_SUCCESS", "Payment received", Money.format(p.d("amount")) + " paid. Your GST invoice is ready.", "/settings/billing");
        audit.log(ws, null, "PAYMENT_SUCCESS", "payments", p.l("id"), null, Map.of("amount", p.d("amount").toString(), "purpose", type));
    }

    private void activateSubscription(Row p, Row w) {
        long ws = w.l("id");
        boolean yearly = "YEARLY".equals(p.s("billing_cycle"));
        Row current = db.row("select * from subscriptions where workspace_id=:ws and status='ACTIVE' order by id desc limit 1", "ws", ws).orElse(null);
        LocalDateTime start = Time.now();
        if (current != null && current.l("plan_id") == p.l("plan_id") && current.t("current_period_end") != null && current.t("current_period_end").isAfter(start)) start = current.t("current_period_end"); // renewal extends
        LocalDateTime end = yearly ? start.plusYears(1) : start.plusMonths(1);
        db.update("update subscriptions set status='COMPLETED' where workspace_id=:ws and status in ('ACTIVE','PENDING','HALTED','CREATED','AUTHENTICATED')", "ws", ws);
        db.insertInto("subscriptions", "workspace_id", ws, "plan_id", p.l("plan_id"), "billing_cycle", p.s("billing_cycle"), "gateway", "RUPIYAX", "status", "ACTIVE",
                "current_period_start", start, "current_period_end", end);
        db.update("update workspaces set plan_id=:p, status='ACTIVE', trial_ends_at=null where id=:id", "p", p.l("plan_id"), "id", ws);
    }

    /** Polled by the browser after returning from the pay page; does the same checked finalisation. */
    public Map<String, Object> status(long ws, long paymentId) {
        Row p = db.row("select * from payments where id=:id and workspace_id=:ws", "id", paymentId, "ws", ws).orElseThrow(() -> ApiException.notFound("Payment"));
        if (List.of("CREATED", "PENDING").contains(p.s("status"))) {
            try {
                settle(p.s("order_ref"));
            } catch (IllegalStateException ignored) {
                // still pending
            }
            p = db.row("select * from payments where id=:id", "id", paymentId).orElseThrow();
        }
        return p.api();
    }


}
