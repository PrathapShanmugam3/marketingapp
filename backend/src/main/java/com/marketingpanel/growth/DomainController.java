package com.marketingpanel.growth;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import com.marketingpanel.common.Hashing;
import com.marketingpanel.config.Settings;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class DomainController {
    private final DomainService svc;
    private final Settings settings;

    public DomainController(DomainService svc, Settings settings) {
        this.svc = svc;
        this.settings = settings;
    }

    @GetMapping("/api/v1/domains")
    @PreAuthorize(Roles.MEMBER)
    public List<Map<String, Object>> list() {
        return svc.list(Auth.ws());
    }

    @PostMapping("/api/v1/domains")
    @PreAuthorize(Roles.OWNER)
    public Map<String, Object> add(@RequestBody DomainService.DomainRequest r) {
        return svc.add(Auth.ws(), r);
    }

    @PostMapping("/api/v1/domains/{id}/verify")
    @PreAuthorize(Roles.OWNER)
    public Map<String, Object> verify(@PathVariable long id) {
        return svc.verify(Auth.ws(), id);
    }

    @PostMapping("/api/v1/domains/{id}/primary")
    @PreAuthorize(Roles.OWNER)
    public Map<String, Object> primary(@PathVariable long id) {
        return svc.makePrimary(Auth.ws(), id);
    }

    @DeleteMapping("/api/v1/domains/{id}")
    @PreAuthorize(Roles.OWNER)
    public Map<String, Object> delete(@PathVariable long id, @RequestParam(defaultValue = "false") boolean confirm) {
        svc.delete(Auth.ws(), id, confirm);
        return Map.of("ok", true);
    }

    /** Caddy `on_demand_tls { ask ... }`: 200 = issue a certificate, anything else = refuse. Internal network or token only. */
    @GetMapping("/internal/tls/ask")
    public ResponseEntity<String> ask(@RequestParam(required = false) String domain, @RequestHeader(name = "X-Internal-Token", required = false) String token,
                                      HttpServletRequest req) {
        boolean local = "127.0.0.1".equals(req.getRemoteAddr()) || "0:0:0:0:0:0:0:1".equals(req.getRemoteAddr()) || req.getRemoteAddr().startsWith("10.")
                || req.getRemoteAddr().startsWith("172.") || req.getRemoteAddr().startsWith("192.168.");
        if (!local && !Hashing.constantEquals(settings.internalToken, token)) return ResponseEntity.status(403).build();
        return svc.allowTls(domain) ? ResponseEntity.ok("ok") : ResponseEntity.status(404).build();
    }
}
