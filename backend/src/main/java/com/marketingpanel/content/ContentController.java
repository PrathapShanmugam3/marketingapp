package com.marketingpanel.content;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class ContentController {
    private final ContentService content;
    private final SeoService seo;

    public ContentController(ContentService content, SeoService seo) {
        this.content = content;
        this.seo = seo;
    }

    @GetMapping("/api/v1/content")
    @PreAuthorize(Roles.MANAGER)
    public List<Map<String, Object>> list(@RequestParam(required = false) String month, @RequestParam(required = false) String status, @RequestParam(required = false) String type) {
        return content.list(Auth.ws(), month, status, type);
    }

    @GetMapping("/api/v1/content/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> get(@PathVariable long id) {
        return content.get(Auth.ws(), id);
    }

    @PostMapping("/api/v1/content")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> create(@RequestBody ContentService.Request r) {
        return content.create(Auth.ws(), Auth.userId(), r);
    }

    @PutMapping("/api/v1/content/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> update(@PathVariable long id, @RequestBody ContentService.Request r) {
        return content.update(Auth.ws(), id, r);
    }

    @DeleteMapping("/api/v1/content/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> delete(@PathVariable long id) {
        content.delete(Auth.ws(), id);
        return Map.of("ok", true);
    }

    @GetMapping("/api/v1/content/{id}/seo-check")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> check(@PathVariable long id) {
        return content.seoCheck(Auth.ws(), id);
    }

    // ---- SEO tools
    public record Url(String url) {}

    @PostMapping("/api/v1/seo/audit")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> audit(@RequestBody Url u) {
        return seo.audit(Auth.ws(), u.url());
    }

    @GetMapping("/api/v1/seo/audits")
    @PreAuthorize(Roles.MANAGER)
    public List<Map<String, Object>> audits() {
        return seo.history(Auth.ws());
    }

    @GetMapping("/api/v1/seo/audits/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> auditDetail(@PathVariable long id) {
        return seo.auditDetail(Auth.ws(), id);
    }

    @GetMapping("/api/v1/seo/keywords")
    @PreAuthorize(Roles.MANAGER)
    public List<Map<String, Object>> keywords() {
        return seo.keywords(Auth.ws());
    }

    @PostMapping("/api/v1/seo/keywords")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> addKeyword(@RequestBody SeoService.KeywordRequest r) {
        return seo.addKeyword(Auth.ws(), r);
    }

    @PostMapping("/api/v1/seo/keywords/{id}/rank")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> rank(@PathVariable long id, @RequestBody SeoService.RankRequest r) {
        return seo.logRank(Auth.ws(), id, r);
    }

    @DeleteMapping("/api/v1/seo/keywords/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> deleteKeyword(@PathVariable long id) {
        seo.deleteKeyword(Auth.ws(), id);
        return Map.of("ok", true);
    }

    // ---- public blog
    @GetMapping(value = "/b/{ws}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> blog(@PathVariable long ws) {
        return ResponseEntity.ok(content.blogIndex(ws));
    }

    @GetMapping(value = "/b/{ws}/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap(@PathVariable long ws) {
        return ResponseEntity.ok(content.sitemap(ws));
    }

    @GetMapping(value = "/b/{ws}/{slug}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> post(@PathVariable long ws, @PathVariable String slug) {
        return ResponseEntity.ok(content.article(ws, slug));
    }
}
