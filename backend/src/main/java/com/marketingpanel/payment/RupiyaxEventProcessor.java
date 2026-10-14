package com.marketingpanel.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.common.Db;
import com.marketingpanel.common.Events;
import com.marketingpanel.common.Json;
import com.marketingpanel.common.Row;
import com.marketingpanel.common.Time;
import com.marketingpanel.jobs.JobHandler;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Processes stored RupiyaX events. Payments are always confirmed by a server-side status check before they count. */
@Component
public class RupiyaxEventProcessor implements JobHandler {
    private final Db db;
    private final PaymentService payments;
    private final ApplicationEventPublisher events;

    public RupiyaxEventProcessor(Db db, PaymentService payments, ApplicationEventPublisher events) {
        this.db = db;
        this.payments = payments;
        this.events = events;
    }

    @Override
    public String type() {
        return "RUPIYAX_EVENT";
    }

    @Override
    public void handle(JsonNode p) {
        Row ev = db.row("select * from webhook_events where id=:id", "id", p.get("id").asLong()).orElse(null);
        if (ev == null || ev.t("processed_at") != null) return;
        JsonNode e = Json.tree(ev.s("payload"));
        JsonNode d = e.path("data");
        String type = e.path("type").asText("");
        try {
            if (type.startsWith("payment.")) {
                payments.settle(d.path("order_ref").asText());
            } else if (type.startsWith("payout.")) {
                String status = switch (type) {
                    case "payout.success" -> "SUCCESS";
                    case "payout.failed" -> "FAILED";
                    case "payout.reversed" -> "REVERSED";
                    default -> "PROCESSING";
                };
                events.publishEvent(new Events.PayoutEvent(d.path("payout_ref").asText(), status, d.path("utr").asText(null), d.path("reason").asText(null)));
            }
        } catch (IllegalStateException notYet) {
            throw notYet; // payment not confirmed yet: retry with backoff, leave unprocessed
        }
        db.update("update webhook_events set processed_at=:now where id=:id", "now", Time.now(), "id", ev.l("id"));
    }
}
