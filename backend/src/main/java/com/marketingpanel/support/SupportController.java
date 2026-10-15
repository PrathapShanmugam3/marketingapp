package com.marketingpanel.support;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/support/tickets")
@PreAuthorize(Roles.AUTHENTICATED)
public class SupportController {
    private final SupportService svc;

    public SupportController(SupportService svc) {
        this.svc = svc;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String status, @RequestParam(required = false) String priority) {
        return svc.list(Auth.user(), status, priority);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody SupportService.TicketRequest r) {
        return svc.create(Auth.user(), r);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return svc.get(Auth.user(), id);
    }

    @PostMapping("/{id}/messages")
    public Map<String, Object> reply(@PathVariable long id, @RequestBody SupportService.MessageRequest r) {
        return svc.reply(Auth.user(), id, r);
    }

    @PatchMapping("/{id}")
    public Map<String, Object> patch(@PathVariable long id, @RequestBody SupportService.Patch p) {
        return svc.patch(Auth.user(), id, p);
    }
}
