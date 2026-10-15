package com.marketingpanel.engage;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import com.marketingpanel.common.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Web-push subscriptions. The public endpoint is called from a customer's browser (landing page / blog) after they allow notifications. */
@RestController
public class PushController {
    private final Db db;

    public PushController(Db db) {
        this.db = db;
    }

    public record Keys(String p256dh, String auth) {}

    public record Subscribe(String endpoint, Keys keys) {}

    @PostMapping("/api/v1/public/push/{ws}/subscribe")
    public Map<String, Object> subscribe(@PathVariable long ws, @RequestBody Subscribe s) {
        if (s.endpoint() == null || !UrlGuard.isPublicHttps(s.endpoint(), 500) || s.keys() == null || s.keys().p256dh() == null || s.keys().auth() == null)
            throw ApiException.badRequest("Invalid subscription.");
        if (!db.exists("select 1 from workspaces where id=:id and status in ('TRIAL','ACTIVE') and deleted_at is null", "id", ws)) throw ApiException.notFound("Workspace");
        if (db.count("select count(*) from push_subscriptions where workspace_id=:ws", "ws", ws) > 200_000) throw ApiException.planLimit("Subscriber limit reached.");
        db.update("insert into push_subscriptions (workspace_id, endpoint, endpoint_hash, p256dh, auth) values (:ws,:e,:h,:p,:a) on duplicate key update active=1, p256dh=:p, auth=:a",
                "ws", ws, "e", s.endpoint(), "h", Hashing.sha256(s.endpoint()), "p", s.keys().p256dh(), "a", s.keys().auth());
        return Map.of("ok", true);
    }

    @GetMapping("/api/v1/push/stats")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> stats() {
        return Map.of("subscribers", db.count("select count(*) from push_subscriptions where workspace_id=:ws and active=1", "ws", Auth.ws()));
    }
}
