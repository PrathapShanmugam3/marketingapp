package com.marketingpanel.payment;

import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import com.marketingpanel.jobs.JobHandler;
import com.marketingpanel.jobs.JobService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** Sandbox pay page: stands in for RupiyaX's hosted payment page while app.rupiyax.mock=true. */
@RestController
@ConditionalOnProperty(name = "app.rupiyax.mock", havingValue = "true", matchIfMissing = true)
public class DevPayController {
    private final MockPaymentGateway gw;
    private final Db db;
    private final JobService jobs;
    private final Settings settings;

    public DevPayController(MockPaymentGateway gw, Db db, JobService jobs, Settings settings) {
        this.gw = gw;
        this.db = db;
        this.jobs = jobs;
        this.settings = settings;
    }

    @GetMapping(value = "/dev/pay/{orderRef}", produces = MediaType.TEXT_HTML_VALUE)
    public String page(@PathVariable String orderRef) {
        Row p = db.row("select * from payments where order_ref=:r", "r", orderRef).orElse(null);
        if (p == null) return Html.shell("Sandbox payment", "<div class=\"card center\"><h1>Unknown order</h1></div>");
        return Html.shell("Sandbox payment", "<div class=\"card center\"><h1>Sandbox payment</h1><p>Pay " + Money.format(p.d("amount")) + " (" + Html.esc(p.s("purpose")) + ")</p>" +
                "<form method=\"post\" action=\"/dev/pay/" + Html.esc(orderRef) + "?result=success\"><button>Pay now (UPI sandbox)</button></form>" +
                "<form method=\"post\" action=\"/dev/pay/" + Html.esc(orderRef) + "?result=failed\"><button style=\"background:#d92d20\">Simulate failure</button></form></div>");
    }

    @PostMapping(value = "/dev/pay/{orderRef}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> pay(@PathVariable String orderRef, @RequestParam(defaultValue = "success") String result) {
        var o = gw.pay(orderRef, "success".equals(result));
        if (o == null) return ResponseEntity.status(404).body("Unknown order");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order_ref", orderRef);
        data.put("amount", o.amount().toPlainString());
        Map<String, Object> evt = new LinkedHashMap<>();
        evt.put("id", "evt_" + Ids.randomCode(12));
        evt.put("type", "success".equals(result) ? "payment.success" : "payment.failed");
        evt.put("data", data);
        String raw = Json.write(evt);
        long id = db.insertInto("webhook_events", "source", "RUPIYAX", "event_id", (String) evt.get("id"), "event_type", (String) evt.get("type"), "payload", raw, "signature_valid", true);
        jobs.enqueue("RUPIYAX_EVENT", Map.of("id", id), Time.now(), 2, 8);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(Html.shell("Done", "<div class=\"card center\"><h1>" + ("success".equals(result) ? "Payment received" : "Payment failed") +
                "</h1><p>You can return to the app.</p><a href=\"" + Html.esc(settings.frontendUrl) + "/settings/billing\">Back to billing</a></div>"));
    }

    /** Settles sandbox payouts a few seconds after creation by sending the same signed webhook RupiyaX would. */
    @org.springframework.context.annotation.Bean
    JobHandler mockPayoutSettle() {
        return new JobHandler() {
            @Override
            public String type() {
                return "MOCK_PAYOUT_SETTLE";
            }

            @Override
            public void handle(JsonNode p) {
                String ref = p.get("payoutRef").asText();
                var fin = gw.finalPayout(ref);
                if (fin == null) return;
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("payout_ref", ref);
                if (fin.utr() != null) data.put("utr", fin.utr());
                if (fin.reason() != null) data.put("reason", fin.reason());
                String evtId = "evt_" + Ids.randomCode(12);
                String raw = Json.write(Map.of("id", evtId, "type", "SUCCESS".equals(fin.status()) ? "payout.success" : "payout.failed", "data", data));
                long id = db.insertInto("webhook_events", "source", "RUPIYAX", "event_id", evtId, "event_type", "SUCCESS".equals(fin.status()) ? "payout.success" : "payout.failed",
                        "payload", raw, "signature_valid", true);
                jobs.enqueue("RUPIYAX_EVENT", Map.of("id", id), Time.now(), 2, 8);
            }
        };
    }
}
