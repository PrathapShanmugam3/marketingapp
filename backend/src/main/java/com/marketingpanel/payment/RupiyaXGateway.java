package com.marketingpanel.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.common.Hashing;
import com.marketingpanel.config.Settings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * RupiyaX adapter. RupiyaX's merchant API documentation is still to be supplied (SRS Appendix B), so the paths, auth
 * and signature scheme below are the assumed contract: JSON over HTTPS, Bearer API key, request body signed with
 * HMAC-SHA256 (api secret) in X-Signature, webhooks signed the same way with the webhook secret. Only this class changes
 * once the real documentation arrives; nothing else in the system depends on these details.
 */
@Component
@ConditionalOnProperty(name = "app.rupiyax.mock", havingValue = "false")
public class RupiyaXGateway implements PaymentGateway {
    private final Settings s;
    private final RestClient http = RestClient.create();

    public RupiyaXGateway(Settings settings) {
        this.s = settings;
    }

    @Override
    public String name() {
        return "RUPIYAX";
    }

    private JsonNode call(String method, String path, Object body) {
        try {
            String json = body == null ? "" : com.marketingpanel.common.Json.write(body);
            var spec = "GET".equals(method) ? http.get().uri(s.rupiyaxBaseUrl + path) : http.post().uri(s.rupiyaxBaseUrl + path).contentType(MediaType.APPLICATION_JSON).body(json);
            return spec.header("Authorization", "Bearer " + s.rupiyaxApiKey).header("X-Signature", Hashing.hmacSha256(s.rupiyaxApiSecret, json))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("RupiyaX error " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
        }
    }

    @Override
    public GatewayOrder createOrder(String orderRef, BigDecimal amount, String purpose, Customer c) {
        JsonNode n = call("POST", "/v1/orders", Map.of("order_ref", orderRef, "amount", amount.toPlainString(), "currency", "INR", "purpose", purpose,
                "customer", Map.of("name", c.name(), "email", c.email(), "phone", c.phone())));
        return new GatewayOrder(n.path("id").asText(), n.path("payment_url").asText());
    }

    @Override
    public PaymentStatus fetchPaymentStatus(String orderRef) {
        JsonNode n = call("GET", "/v1/orders/" + orderRef, null);
        return new PaymentStatus(n.path("status").asText("PENDING").toUpperCase(), new BigDecimal(n.path("amount").asText("0")), n.path("currency").asText("INR"),
                n.path("order_ref").asText(orderRef), n.path("txn_id").asText(null), n.path("utr").asText(null), n.path("method").asText(null));
    }

    @Override
    public boolean verifyWebhook(String rawBody, Map<String, String> headers) {
        String sig = headers.getOrDefault("x-signature", headers.get("x-rupiyax-signature"));
        return sig != null && Hashing.constantEquals(Hashing.hmacSha256(s.rupiyaxWebhookSecret, rawBody), sig.toLowerCase());
    }

    @Override
    public PayoutResult createPayout(String payoutRef, BigDecimal amount, PayoutMethod m) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("payout_ref", payoutRef); // our idempotency key
        body.put("amount", amount.toPlainString());
        body.put("mode", m.mode());
        if ("UPI".equals(m.type())) body.put("upi_id", m.upiId());
        else {
            body.put("account_number", m.accountNo());
            body.put("ifsc", m.ifsc());
        }
        body.put("beneficiary_name", m.holderName());
        JsonNode n = call("POST", "/v1/payouts", body);
        return new PayoutResult(n.path("id").asText(), n.path("status").asText("PROCESSING").toUpperCase());
    }

    @Override
    public PayoutStatus fetchPayoutStatus(String payoutRef) {
        JsonNode n = call("GET", "/v1/payouts/" + payoutRef, null);
        return new PayoutStatus(n.path("status").asText("PROCESSING").toUpperCase(), n.path("utr").asText(null), n.path("failure_reason").asText(null));
    }

    @Override
    public VpaCheck verifyUpi(String upiId) {
        JsonNode n = call("POST", "/v1/vpa/verify", Map.of("upi_id", upiId));
        return new VpaCheck(n.path("valid").asBoolean(false), n.path("name").asText(null));
    }

    @Override
    public BankCheck pennyDrop(String accountNo, String ifsc, String declaredName) {
        JsonNode n = call("POST", "/v1/bank/verify", Map.of("account_number", accountNo, "ifsc", ifsc));
        return new BankCheck(n.path("valid").asBoolean(false), n.path("name").asText(null));
    }

    @Override
    public BigDecimal payoutAccountBalance() {
        return new BigDecimal(call("GET", "/v1/balance", null).path("available").asText("0"));
    }

    @Override
    public List<SettlementLine> settlements(LocalDate day) {
        List<SettlementLine> out = new ArrayList<>();
        for (JsonNode l : call("GET", "/v1/settlements?date=" + day, null).path("lines"))
            out.add(new SettlementLine(l.path("ref").asText(), l.path("kind").asText(), new BigDecimal(l.path("amount").asText("0"))));
        return out;
    }
}
