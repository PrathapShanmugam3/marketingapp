package com.marketingpanel.payment;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Picks the adapter named in a row's `gateway` column. */
@Component
public class GatewayRegistry {
    private final Map<String, PaymentGateway> by = new HashMap<>();

    public GatewayRegistry(List<PaymentGateway> gateways) {
        for (PaymentGateway g : gateways) by.put(g.name(), g);
    }

    public PaymentGateway get(String name) {
        PaymentGateway g = by.get(name);
        if (g == null) throw new IllegalStateException("No payment gateway adapter for " + name);
        return g;
    }

    public PaymentGateway primary() {
        return get("RUPIYAX");
    }
}
