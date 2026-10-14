package com.marketingpanel.whatsapp;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/templates")
public class TemplateController {
    private final TemplateService svc;

    public TemplateController(TemplateService svc) {
        this.svc = svc;
    }

    @GetMapping
    @PreAuthorize(Roles.MEMBER)
    public List<Map<String, Object>> list(@RequestParam(required = false) String status, @RequestParam(required = false) String category) {
        return svc.list(Auth.ws(), status, category);
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> get(@PathVariable long id) {
        return svc.get(Auth.ws(), id);
    }

    @PostMapping
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> create(@RequestBody TemplateService.TemplateRequest r) {
        return svc.create(Auth.ws(), Auth.userId(), r);
    }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> update(@PathVariable long id, @RequestBody TemplateService.TemplateRequest r) {
        return svc.update(Auth.ws(), id, r);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> submit(@PathVariable long id) {
        return svc.submit(Auth.ws(), id);
    }

    @PostMapping("/sync")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> sync() {
        return Map.of("updated", svc.sync(Auth.ws()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> delete(@PathVariable long id) {
        svc.delete(Auth.ws(), id);
        return Map.of("ok", true);
    }
}
