package com.marketingpanel.engage;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/broadcasts")
@PreAuthorize(Roles.MANAGER)
public class BroadcastController {
    private final BroadcastService svc;

    public BroadcastController(BroadcastService svc) {
        this.svc = svc;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String channel) {
        return svc.list(Auth.ws(), channel);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody BroadcastService.Request r) {
        return svc.create(Auth.ws(), Auth.userId(), r);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return svc.get(Auth.ws(), id);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody BroadcastService.Request r) {
        return svc.update(Auth.ws(), id, r);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        svc.delete(Auth.ws(), id);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/estimate")
    public Map<String, Object> estimate(@PathVariable long id) {
        return svc.estimate(Auth.ws(), id);
    }

    @PostMapping("/{id}/send")
    public Map<String, Object> send(@PathVariable long id) {
        return svc.send(Auth.ws(), Auth.userId(), id);
    }

    public record When(String scheduledAt) {}

    @PostMapping("/{id}/schedule")
    public Map<String, Object> schedule(@PathVariable long id, @RequestBody When w) {
        return svc.schedule(Auth.ws(), id, w.scheduledAt());
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable long id) {
        return svc.cancel(Auth.ws(), id);
    }

    public record To(String to) {}

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable long id, @RequestBody To t) {
        return svc.testSend(Auth.ws(), id, t.to());
    }

    @GetMapping("/{id}/recipients")
    public Map<String, Object> recipients(@PathVariable long id, @RequestParam(required = false) String status) {
        return svc.recipients(Auth.ws(), id, status);
    }

    public record OptOut(long contactId, String channel, boolean out) {}

    @PostMapping("/opt-out")
    public Map<String, Object> optOut(@RequestBody OptOut o) {
        svc.optOut(Auth.ws(), o.contactId(), o.channel(), o.out());
        return Map.of("ok", true);
    }
}
