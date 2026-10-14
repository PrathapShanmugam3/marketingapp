package com.marketingpanel.growth;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import com.marketingpanel.common.ClientInfo;
import com.marketingpanel.common.Html;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
public class LinkController {
    private final LinkService svc;
    private final com.marketingpanel.common.RateLimiter limiter;

    public LinkController(LinkService svc, com.marketingpanel.common.RateLimiter limiter) {
        this.svc = svc;
        this.limiter = limiter;
    }

    @GetMapping("/api/v1/links")
    @PreAuthorize(Roles.MEMBER)
    public List<Map<String, Object>> list() {
        return svc.list(Auth.ws());
    }

    @PostMapping("/api/v1/links")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> create(@RequestBody LinkService.LinkRequest r) {
        return svc.create(Auth.ws(), Auth.userId(), r);
    }

    public record Toggle(boolean active) {}

    @PatchMapping("/api/v1/links/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> toggle(@PathVariable long id, @RequestBody Toggle t) {
        return svc.toggle(Auth.ws(), id, t.active());
    }

    @DeleteMapping("/api/v1/links/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> delete(@PathVariable long id) {
        svc.delete(Auth.ws(), id);
        return Map.of("ok", true);
    }

    @GetMapping("/api/v1/links/{id}/stats")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> stats(@PathVariable long id) {
        return svc.stats(Auth.ws(), id);
    }

    @GetMapping(value = "/api/v1/links/{id}/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    @PreAuthorize(Roles.MEMBER)
    public byte[] qr(@PathVariable long id) {
        return svc.qr(Auth.ws(), id);
    }

    /** Public redirect: 302 in under 100 ms; redirects limited to 30/s per IP. */
    @GetMapping("/l/{code}")
    public ResponseEntity<String> go(@PathVariable String code, @RequestParam(name = "c", required = false) String contact,
                                     @RequestHeader(name = "User-Agent", required = false) String ua, @RequestHeader(name = "Referer", required = false) String ref,
                                     HttpServletRequest req) {
        String ip = ClientInfo.ip(req);
        if (!limiter.tryConsume("redir:" + ip, 30, java.time.Duration.ofSeconds(1))) return ResponseEntity.status(429).header("Retry-After", "1").build();
        LinkService.Redirect r = svc.resolve(code, ua, ref, ip, contact);
        return switch (r.status()) {
            case 302 -> ResponseEntity.status(302).location(URI.create(r.location())).cacheControl(CacheControl.noStore()).build();
            case 410 -> ResponseEntity.status(410).contentType(MediaType.TEXT_HTML).body(Html.ended("This link is no longer active."));
            default -> ResponseEntity.status(404).contentType(MediaType.TEXT_HTML).body(Html.shell("Not found", "<div class=\"card center\"><h1>Link not found</h1></div>"));
        };
    }
}
