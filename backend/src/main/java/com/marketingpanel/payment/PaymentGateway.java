package com.marketingpanel.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Payment gateway port (SRS 3.6). RupiyaXGateway implements it; another gateway (e.g. Razorpay) can be added without
 * touching other modules. The adapter is selected per row by the `gateway` column so old transactions keep their adapter.
 */
public interface PaymentGateway {
    record Customer(String name, String email, String phone) {}

    record GatewayOrder(String gatewayOrderId, String payUrl) {}

    record PaymentStatus(String status, BigDecimal amount, String currency, String orderRef, String txnId, String utr, String method) {}

    record PayoutMethod(String type, String upiId, String accountNo, String ifsc, String holderName, String mode) {}

    record PayoutResult(String gatewayPayoutId, String status) {}

    record PayoutStatus(String status, String utr, String reason) {}

    record VpaCheck(boolean valid, String holderName) {}

    record BankCheck(boolean valid, String holderName) {}

    record SettlementLine(String ref, String kind, BigDecimal amount) {}

    String name();

    GatewayOrder createOrder(String orderRef, BigDecimal amount, String purpose, Customer c);

    /** Server-side status check; a payment is final only when the signed webhook and this call agree. */
    PaymentStatus fetchPaymentStatus(String orderRef);

    boolean verifyWebhook(String rawBody, Map<String, String> headers);

    /** Idempotent on payoutRef: a retry never creates a second transfer. */
    PayoutResult createPayout(String payoutRef, BigDecimal amount, PayoutMethod method);

    PayoutStatus fetchPayoutStatus(String payoutRef);

    VpaCheck verifyUpi(String upiId);

    /** Re 1 penny-drop. declaredName lets sandbox adapters echo a matching holder name. */
    BankCheck pennyDrop(String accountNo, String ifsc, String declaredName);

    /** Balance of the platform's payout account at the gateway. */
    BigDecimal payoutAccountBalance();

    List<SettlementLine> settlements(LocalDate day);
}
