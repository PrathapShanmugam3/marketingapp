package com.marketingpanel.growth;

import com.marketingpanel.common.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/** P31 Developer API keys: full key shown once, only its SHA-256 is stored; 60 requests/min per key. */
@Service
public class ApiKeyService {
    static final Set<String> SCOPES = Set.of("contacts:read", "contacts:write", "leads:write", "automations:trigger", "links:write");
    private final Db db;
    private final PlanService plans;
    private final RateLimiter limiter;
    private final AuditService audit;

    public ApiKeyService(Db db, PlanService plans, RateLimiter limiter, AuditService audit) {
        this.db = db;
        this.plans = plans;
        this.limiter = limiter;
        this.audit = audit;
    }

    public record KeyRequest(String name, List<String> scopes, String expiresAt) {}

    public List<Map<String, Object>> list(long ws) {
        return db.list("select id, name, key_prefix, scopes, last_used_at, expires_at, revoked_at, created_at from api_keys where workspace_id=:ws order by id desc", "ws", ws);
    }

    public Map<String, Object> create(long ws, long userId, KeyRequest r) {
        if ("STARTER".equals(plans.plan(ws).s("code"))) throw ApiException.planLimit("API keys are available on Growth and Agency plans.");
        List<FieldErr> errs = new ArrayList<>();
        String name = Norm.plain(Norm.name(r.name()));
        if (name == null || name.length() < 3 || name.length() > 50) errs.add(new FieldErr("name", "key.name", "Enter a name for this key."));
        List<String> scopes = r.scopes() == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(r.scopes()));
        if (scopes.isEmpty() || !SCOPES.containsAll(scopes)) errs.add(new FieldErr("scopes", "key.scopes", "Choose at least one permission."));
        LocalDateTime exp = null;
        if (r.expiresAt() != null && !r.expiresAt().isBlank()) {
            exp = Time.parseUtc(r.expiresAt());
            if (exp.isBefore(Time.now()) || exp.isAfter(Time.now().plusDays(366))) errs.add(new FieldErr("expiresAt", "key.expiry", "Expiry can be at most 1 year away."));
        }
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        long active = db.count("select count(*) from api_keys where workspace_id=:ws and revoked_at is null and (expires_at is null or expires_at > :now)", "ws", ws, "now", Time.now());
        if (active >= 5) throw ApiException.planLimit("You can have at most 5 active API keys.");
        String key = "mp_live_" + Ids.randomCode(32);
        long id = db.insertInto("api_keys", "workspace_id", ws, "name", name, "key_prefix", key.substring(0, 8), "key_hash", Hashing.sha256(key),
                "scopes", Json.write(scopes), "expires_at", exp, "created_by", userId);
        audit.log("API_KEY_CREATED", "api_keys", id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("key", key); // shown once
        out.put("name", name);
        out.put("scopes", scopes);
        return out;
    }

    public void revoke(long ws, long id) {
        int n = db.update("update api_keys set revoked_at=:now where id=:id and workspace_id=:ws and revoked_at is null", "now", Time.now(), "id", id, "ws", ws);
        if (n == 0) throw ApiException.notFound("API key");
        audit.log("API_KEY_REVOKED", "api_keys", id);
    }

    public record Authenticated(long keyId, long workspaceId, Set<String> scopes) {}

    /** Validates the key, the scope and the 60/min limit (HTTP 429 with Retry-After). */
    public Authenticated authenticate(String header, String bearer, String scope) {
        String key = header != null && !header.isBlank() ? header.trim() : bearer != null && bearer.startsWith("Bearer ") ? bearer.substring(7).trim() : null;
        if (key == null || !key.startsWith("mp_live_")) throw ApiException.unauthorized("A valid API key is required.");
        Row k = db.row("select * from api_keys where key_hash=:h and revoked_at is null and (expires_at is null or expires_at > :now)", "h", Hashing.sha256(key), "now", Time.now())
                .orElseThrow(() -> ApiException.unauthorized("A valid API key is required."));
        limiter.check("apikey:" + k.l("id"), 60, Duration.ofMinutes(1));
        Set<String> scopes = new HashSet<>();
        Json.list(k.s("scopes")).forEach(o -> scopes.add(String.valueOf(o)));
        if (!scopes.contains(scope)) throw ApiException.forbidden("This key does not have the " + scope + " permission.");
        db.update("update api_keys set last_used_at=:now where id=:id", "now", Time.now(), "id", k.l("id"));
        return new Authenticated(k.l("id"), k.l("workspace_id"), scopes);
    }
}
