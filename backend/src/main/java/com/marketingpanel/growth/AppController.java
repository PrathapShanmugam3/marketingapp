package com.marketingpanel.growth;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/apps")
@PreAuthorize(Roles.MANAGER)
public class AppController {
    private final AppService svc;

    public AppController(AppService svc) {
        this.svc = svc;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return svc.list(Auth.ws());
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return svc.get(Auth.ws(), id);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody AppService.AppRequest r) {
        return svc.create(Auth.ws(), Auth.userId(), r);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody AppService.AppRequest r) {
        return svc.update(Auth.ws(), id, r);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        svc.delete(Auth.ws(), id);
        return Map.of("ok", true);
    }
}
