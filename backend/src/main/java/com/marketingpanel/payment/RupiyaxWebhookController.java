package com.marketingpanel.payment;

import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import com.marketingpanel.jobs.JobService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** RupiyaX payment and payout events: verify signature, store with a unique event id, answer 200, process in a job. */
@RestController
public class RupiyaxWebhookController {
    private final GatewayRegistry gateways;
    private final Db db;
    private final JobService jobs;

    public RupiyaxWebhookController(GatewayRegistry gateways, Db db, JobService jobs) {
        this.gateways = gateways;
        this.db = db;
        this.jobs = jobs;
    }

    @PostMapping("/webhooks/rupiyax")
    public ResponseEntity<Map<String, Object>> receive(@RequestBody String raw, HttpServletRequest req) {
        Map<String, String> headers = new HashMap<>();
        Collections.list(req.getHeaderNames()).forEach(h -> headers.put(h.toLowerCase(), req.getHeader(h)));
        if (!gateways.primary().verifyWebhook(raw, headers)) return ResponseEntity.status(403).body(Map.of("ok", false));
        com.fasterxml.jackson.databind.JsonNode n;
        try {
            n = Json.tree(raw);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false));
        }
        String eventId = n.hasNonNull("id") ? n.get("id").asText() : Hashing.sha256(raw);
        try {
            long id = db.insertInto("webhook_events", "source", "RUPIYAX", "event_id", eventId.length() > 100 ? Hashing.sha256(eventId) : eventId,
                    "event_type", n.path("type").asText("unknown"), "payload", raw, "signature_valid", true);
            jobs.enqueue("RUPIYAX_EVENT", Map.of("id", id), Time.now(), 2, 8);
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // duplicate delivery: no-op (idempotent)
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
