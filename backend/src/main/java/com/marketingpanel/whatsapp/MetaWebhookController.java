package com.marketingpanel.whatsapp;

import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import com.marketingpanel.jobs.JobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Meta webhooks: verify the HMAC, store the raw event (dedupe on payload hash), answer 200 fast, process in a job. */
@RestController
public class MetaWebhookController {
    private final Settings settings;
    private final Db db;
    private final JobService jobs;

    public MetaWebhookController(Settings settings, Db db, JobService jobs) {
        this.settings = settings;
        this.db = db;
        this.jobs = jobs;
    }

    @GetMapping("/webhooks/meta")
    public ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
                                         @RequestParam(name = "hub.verify_token", required = false) String token,
                                         @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode) && Hashing.constantEquals(settings.metaVerifyToken, token) && challenge != null)
            return ResponseEntity.ok(challenge);
        return ResponseEntity.status(403).body("forbidden");
    }

    @PostMapping("/webhooks/meta")
    public ResponseEntity<Map<String, Object>> receive(@RequestBody String raw, @RequestHeader(name = "X-Hub-Signature-256", required = false) String sig) {
        boolean valid;
        if (settings.metaAppSecret.isBlank()) {
            valid = settings.whatsappMock; // sandbox only: no app secret to check against
        } else {
            valid = sig != null && Hashing.constantEquals("sha256=" + Hashing.hmacSha256(settings.metaAppSecret, raw), sig);
        }
        if (!valid) return ResponseEntity.status(403).body(Map.of("ok", false));
        String eventId = Hashing.sha256(raw);
        try {
            long id = db.insertInto("webhook_events", "source", "META", "event_id", eventId, "event_type", "whatsapp", "payload", raw, "signature_valid", true);
            jobs.enqueue("WEBHOOK_PROCESS", Map.of("id", id), Time.now(), 2, 5);
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // duplicate delivery: already stored
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("ok", false));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
