package com.marketingpanel.growth;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/automations")
public class AutomationController {
    private final AutomationService svc;

    public AutomationController(AutomationService svc) {
        this.svc = svc;
    }

    @GetMapping
    @PreAuthorize(Roles.MEMBER)
    public List<Map<String, Object>> list() {
        return svc.list(Auth.ws());
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> get(@PathVariable long id) {
        return svc.get(Auth.ws(), id);
    }

    @PostMapping
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> create(@RequestBody AutomationService.AutomationRequest r) {
        return svc.create(Auth.ws(), Auth.userId(), r);
    }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> update(@PathVariable long id, @RequestBody AutomationService.AutomationRequest r) {
        return svc.update(Auth.ws(), id, r);
    }

    public record Status(String status) {}

    @PatchMapping("/{id}/status")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> status(@PathVariable long id, @RequestBody Status s) {
        return svc.setStatus(Auth.ws(), id, s.status());
    }

    @GetMapping("/{id}/runs")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> runs(@PathVariable long id) {
        return svc.runs(Auth.ws(), id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> delete(@PathVariable long id) {
        svc.delete(Auth.ws(), id);
        return Map.of("ok", true);
    }
}
