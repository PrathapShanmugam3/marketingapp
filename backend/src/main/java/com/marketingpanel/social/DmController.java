package com.marketingpanel.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class DmController {
    private final DmService dm;
    private final Settings settings;

    public DmController(DmService dm, Settings settings) {
        this.dm = dm;
        this.settings = settings;
    }

    @GetMapping("/api/v1/social/dm/threads")
    @PreAuthorize(Roles.MEMBER)
    public List<Map<String, Object>> threads(@RequestParam(required = false) String status, @RequestParam(defaultValue = "false") boolean unread) {
        return dm.threads(Auth.ws(), status, unread);
    }

    @GetMapping("/api/v1/social/dm/threads/{id}")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> messages(@PathVariable long id) {
        return dm.messages(Auth.ws(), id);
    }

    public record Reply(String text) {}

    @PostMapping("/api/v1/social/dm/threads/{id}/messages")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> reply(@PathVariable long id, @RequestBody Reply r) {
        return dm.reply(Auth.ws(), Auth.userId(), id, r.text());
    }

    public record Status(String status) {}

    @PatchMapping("/api/v1/social/dm/threads/{id}")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> status(@PathVariable long id, @RequestBody Status s) {
        return dm.setStatus(Auth.ws(), id, s.status());
    }

    /** Sandbox only: injects an inbound DM so the inbox can be tried without a Meta app. */
    public record Simulate(String platform, String pageId, String senderId, String senderName, String text) {}

    @PostMapping("/api/v1/social/dm/simulate")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> simulate(@RequestBody Simulate s) {
        if (!settings.socialMock) throw ApiException.forbidden("Simulated messages are only available in sandbox mode.");
        boolean ok = dm.ingest(s.platform() == null ? "INSTAGRAM" : s.platform(), s.pageId(), s.senderId() == null ? "sim-user" : s.senderId(), s.senderName(), "sim-" + Ids.randomCode(10), s.text());
        if (!ok) throw ApiException.state("Connect the account first (and make sure your plan includes DMs).");
        return Map.of("ok", true);
    }

    // ---- Meta webhook for Messenger / Instagram messaging
    @GetMapping("/webhooks/social")
    public ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode, @RequestParam(name = "hub.verify_token", required = false) String token,
                                         @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode) && Hashing.constantEquals(settings.metaVerifyToken, token) && challenge != null) return ResponseEntity.ok(challenge);
        return ResponseEntity.status(403).body("forbidden");
    }

    @PostMapping("/webhooks/social")
    public ResponseEntity<Map<String, Object>> receive(@RequestBody String raw, @RequestHeader(name = "X-Hub-Signature-256", required = false) String sig) {
        boolean valid = settings.metaAppSecret.isBlank() ? settings.socialMock : sig != null && Hashing.constantEquals("sha256=" + Hashing.hmacSha256(settings.metaAppSecret, raw), sig);
        if (!valid) return ResponseEntity.status(403).body(Map.of("ok", false));
        try {
            JsonNode root = Json.tree(raw);
            dm.ingestWebhook(root);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("ok", false));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
