package com.marketingpanel.payment;

import com.marketingpanel.common.Hashing;
import com.marketingpanel.common.Ids;
import com.marketingpanel.common.Time;
import com.marketingpanel.config.Settings;
import com.marketingpanel.jobs.JobService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sandbox gateway (app.rupiyax.mock=true, the default) so the whole money flow can be exercised without RupiyaX credentials.
 * Orders are paid from the dev page /dev/pay/{orderRef}; payouts settle automatically a few seconds after creation.
 * Sandbox rules: UPI ids / accounts containing "invalid" or ending in 0000 fail verification; payouts above Rs 49,000 fail.
 */
@Component
@ConditionalOnProperty(name = "app.rupiyax.mock", havingValue = "true", matchIfMissing = true)
public class MockPaymentGateway implements PaymentGateway {
    public record Order(String ref, BigDecimal amount, String status, String txnId, String utr) {}

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, PayoutStatus> payouts = new ConcurrentHashMap<>();
    private final Settings settings;
    private final JobService jobs;

    public MockPaymentGateway(Settings settings, JobService jobs) {
        this.settings = settings;
        this.jobs = jobs;
    }

    @Override
    public String name() {
        return "RUPIYAX";
    }

    @Override
    public GatewayOrder createOrder(String orderRef, BigDecimal amount, String purpose, Customer c) {
        orders.put(orderRef, new Order(orderRef, amount, "PENDING", null, null));
        return new GatewayOrder("mock_" + orderRef, settings.publicBaseUrl + "/dev/pay/" + orderRef);
    }

    /** Called by the sandbox pay page. */
    public Order pay(String orderRef, boolean success) {
        Order o = orders.get(orderRef);
        if (o == null) return null;
        Order n = new Order(orderRef, o.amount(), success ? "SUCCESS" : "FAILED", success ? "txn_" + Ids.randomCode(10) : null, success ? Ids.randomCode(12).toUpperCase() : null);
        orders.put(orderRef, n);
        return n;
    }

    /** Test hook: pretend the gateway reports a different amount than we asked for. */
    public void forceAmount(String orderRef, BigDecimal amount) {
        Order o = orders.get(orderRef);
        if (o != null) orders.put(orderRef, new Order(o.ref(), amount, o.status(), o.txnId(), o.utr()));
    }

    @Override
    public PaymentStatus fetchPaymentStatus(String orderRef) {
        Order o = orders.get(orderRef);
        if (o == null) return new PaymentStatus("NOT_FOUND", BigDecimal.ZERO, "INR", orderRef, null, null, null);
        return new PaymentStatus(o.status(), o.amount(), "INR", o.ref(), o.txnId(), o.utr(), "upi");
    }

    @Override
    public boolean verifyWebhook(String rawBody, Map<String, String> headers) {
        String sig = headers.getOrDefault("x-signature", headers.get("x-rupiyax-signature"));
        return sig != null && Hashing.constantEquals(Hashing.hmacSha256(settings.rupiyaxWebhookSecret, rawBody), sig.toLowerCase());
    }

    public String sign(String rawBody) {
        return Hashing.hmacSha256(settings.rupiyaxWebhookSecret, rawBody);
    }

    @Override
    public PayoutResult createPayout(String payoutRef, BigDecimal amount, PayoutMethod m) {
        if (payouts.containsKey(payoutRef)) return new PayoutResult("po_" + payoutRef, "PROCESSING"); // idempotent
        boolean fail = amount.compareTo(new BigDecimal("49000")) > 0;
        payouts.put(payoutRef, fail ? new PayoutStatus("FAILED", null, "Beneficiary bank rejected the transfer (sandbox)")
                : new PayoutStatus("SUCCESS", "UTR" + Ids.randomCode(12).toUpperCase(), null));
        jobs.enqueue("MOCK_PAYOUT_SETTLE", Map.of("payoutRef", payoutRef), Time.now().plusSeconds(3));
        return new PayoutResult("po_" + payoutRef, "PROCESSING");
    }

    public PayoutStatus finalPayout(String payoutRef) {
        return payouts.get(payoutRef);
    }

    @Override
    public PayoutStatus fetchPayoutStatus(String payoutRef) {
        PayoutStatus p = payouts.get(payoutRef);
        return p == null ? new PayoutStatus("NOT_FOUND", null, null) : p;
    }

    @Override
    public VpaCheck verifyUpi(String upiId) {
        String lower = upiId.toLowerCase();
        if (lower.contains("invalid") || lower.startsWith("0000")) return new VpaCheck(false, null);
        String local = lower.substring(0, lower.indexOf('@')).replaceAll("[^a-z]+", " ").trim();
        return new VpaCheck(true, local.isEmpty() ? "SANDBOX USER" : local.toUpperCase());
    }

    @Override
    public BankCheck pennyDrop(String accountNo, String ifsc, String declaredName) {
        if (accountNo.endsWith("0000")) return new BankCheck(false, null);
        if (accountNo.endsWith("1111")) return new BankCheck(true, "SOMEONE ELSE ENTIRELY");
        return new BankCheck(true, declaredName == null ? "SANDBOX USER" : declaredName.toUpperCase());
    }

    @Override
    public BigDecimal payoutAccountBalance() {
        return new BigDecimal("10000000");
    }

    @Override
    public List<SettlementLine> settlements(LocalDate day) {
        List<SettlementLine> out = new ArrayList<>();
        for (Order o : orders.values()) if ("SUCCESS".equals(o.status())) out.add(new SettlementLine(o.ref(), "PAYMENT", o.amount()));
        return out;
    }
}
