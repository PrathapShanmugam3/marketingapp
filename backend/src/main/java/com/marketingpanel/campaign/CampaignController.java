package com.marketingpanel.campaign;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/campaigns")
public class CampaignController {
    private final CampaignService svc;

    public CampaignController(CampaignService svc) {
        this.svc = svc;
    }

    @GetMapping
    @PreAuthorize(Roles.MEMBER)
    public List<Map<String, Object>> list(@RequestParam(required = false) String status, @RequestParam(required = false) String q) {
        return svc.list(Auth.ws(), status, q);
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> get(@PathVariable long id) {
        return svc.get(Auth.ws(), id);
    }

    @PostMapping
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> create(@Valid @RequestBody CampaignDtos.CampaignRequest r) {
        return svc.create(Auth.ws(), Auth.userId(), r);
    }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> update(@PathVariable long id, @Valid @RequestBody CampaignDtos.CampaignRequest r) {
        return svc.update(Auth.ws(), id, r);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> delete(@PathVariable long id) {
        svc.delete(Auth.ws(), id);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/estimate")
    @PreAuthorize(Roles.MANAGER)
    public CampaignService.Estimate estimate(@PathVariable long id) {
        return svc.estimate(Auth.ws(), id);
    }

    @PostMapping("/{id}/test-send")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> testSend(@PathVariable long id, @Valid @RequestBody CampaignDtos.TestSend r) {
        return svc.testSend(Auth.ws(), Auth.userId(), id, r.phone());
    }

    /** send, schedule, pause, resume, cancel, duplicate */
    @PostMapping("/{id}/{action}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> action(@PathVariable long id, @PathVariable String action, @RequestBody(required = false) Map<String, Object> body) {
        return svc.act(Auth.user(), id, action, body);
    }

    @GetMapping("/{id}/messages")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> messages(@PathVariable long id, @RequestParam(required = false) String status, @RequestParam(required = false) String q,
                                        @RequestParam(required = false) Long cursor, @RequestParam(defaultValue = "50") int size) {
        return svc.messages(Auth.ws(), id, status, q, cursor, size);
    }

    @GetMapping("/{id}/messages.csv")
    @PreAuthorize(Roles.MANAGER)
    public ResponseEntity<byte[]> csv(@PathVariable long id) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=campaign-" + id + ".csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8")).body(svc.messagesCsv(Auth.ws(), id).getBytes(StandardCharsets.UTF_8));
    }
}
