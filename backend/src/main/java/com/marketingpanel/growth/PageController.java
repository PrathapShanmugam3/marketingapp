package com.marketingpanel.growth;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import com.marketingpanel.common.Html;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class PageController {
    private final PageService svc;

    public PageController(PageService svc) {
        this.svc = svc;
    }

    @GetMapping("/api/v1/pages")
    @PreAuthorize(Roles.MANAGER)
    public List<Map<String, Object>> list() {
        return svc.list(Auth.ws());
    }

    @GetMapping("/api/v1/pages/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> get(@PathVariable long id) {
        return svc.get(Auth.ws(), id);
    }

    @PostMapping("/api/v1/pages")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> create(@RequestBody PageService.PageRequest r) {
        return svc.create(Auth.ws(), Auth.userId(), r);
    }

    @PutMapping("/api/v1/pages/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> update(@PathVariable long id, @RequestBody PageService.PageRequest r) {
        return svc.update(Auth.ws(), id, r);
    }

    @PostMapping("/api/v1/pages/{id}/publish")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> publish(@PathVariable long id) {
        return svc.publish(Auth.ws(), id, true);
    }

    @PostMapping("/api/v1/pages/{id}/unpublish")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> unpublish(@PathVariable long id) {
        return svc.publish(Auth.ws(), id, false);
    }

    @DeleteMapping("/api/v1/pages/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> delete(@PathVariable long id) {
        svc.delete(Auth.ws(), id);
        return Map.of("ok", true);
    }

    private static final String CSP = "default-src 'self'; img-src 'self' data: https:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; frame-src 'self'; frame-ancestors *";

    /** Platform-domain landing page. */
    @GetMapping(value = "/p/{ws}/{path}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> platform(@PathVariable long ws, @PathVariable String path) {
        return page(svc.render(ws, null, path));
    }

    /** Custom-domain landing page: the edge (Caddy) rewrites https://offers.shop.in/foo to /p/_domain/foo and keeps the Host header. */
    @GetMapping(value = "/p/_domain/{path}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> custom(@PathVariable String path, HttpServletRequest req) {
        String host = req.getHeader("X-Forwarded-Host") != null ? req.getHeader("X-Forwarded-Host") : req.getServerName();
        return page(svc.render(null, host.split(",")[0].trim().replaceAll(":\\d+$", ""), path));
    }

    private ResponseEntity<String> page(java.util.Optional<String> html) {
        if (html.isPresent()) return ResponseEntity.ok().header("Content-Security-Policy", CSP).body(html.get());
        return ResponseEntity.status(404).contentType(MediaType.TEXT_HTML).body(Html.shell("Not found", "<div class=\"card center\"><h1>Page not found</h1></div>"));
    }
}
