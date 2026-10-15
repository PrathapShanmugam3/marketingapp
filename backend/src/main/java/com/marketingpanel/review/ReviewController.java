package com.marketingpanel.review;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
public class ReviewController {
    private final ReviewService svc;

    public ReviewController(ReviewService svc) {
        this.svc = svc;
    }

    @GetMapping("/api/v1/reviews/settings")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> settings() {
        return svc.settings(Auth.ws());
    }

    @PutMapping("/api/v1/reviews/settings")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> save(@RequestBody ReviewService.SettingsRequest r) {
        return svc.saveSettings(Auth.ws(), r);
    }

    public record RequestBodyDto(Long contactId) {}

    @PostMapping("/api/v1/reviews/requests")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> request(@RequestBody RequestBodyDto r) {
        if (r.contactId() == null) throw com.marketingpanel.common.ApiException.invalid("contactId", "review.contact", "Choose a contact.");
        return svc.request(Auth.ws(), r.contactId());
    }

    @GetMapping("/api/v1/reviews/requests")
    @PreAuthorize(Roles.MEMBER)
    public List<Map<String, Object>> requests() {
        return svc.requests(Auth.ws());
    }

    @GetMapping("/api/v1/reviews/stats")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> stats() {
        return svc.stats(Auth.ws());
    }

    public record Manual(double rating, int reviewCount) {}

    @PostMapping("/api/v1/reviews/stats")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> manual(@RequestBody Manual m) {
        svc.recordManual(Auth.ws(), m.rating(), m.reviewCount());
        return svc.stats(Auth.ws());
    }

    @GetMapping(value = "/api/v1/reviews/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    @PreAuthorize(Roles.MEMBER)
    public byte[] qr() {
        return svc.qr(Auth.ws());
    }

    @GetMapping("/api/v1/reviews/poster.pdf")
    @PreAuthorize(Roles.MEMBER)
    public ResponseEntity<byte[]> poster(@RequestParam(defaultValue = "A4") String size) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=review-poster.pdf")
                .body(svc.poster(Auth.ws(), size));
    }

    /** Public review redirect (counts the first open). */
    @GetMapping("/r/{code}")
    public ResponseEntity<Void> go(@PathVariable String code) {
        return ResponseEntity.status(302).location(URI.create(svc.redirect(code))).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
}
