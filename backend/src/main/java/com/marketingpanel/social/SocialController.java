package com.marketingpanel.social;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import com.marketingpanel.common.Time;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/social")
public class SocialController {
    private final SocialService svc;

    public SocialController(SocialService svc) {
        this.svc = svc;
    }

    @GetMapping("/accounts")
    @PreAuthorize(Roles.MANAGER)
    public List<Map<String, Object>> accounts() {
        return svc.accounts(Auth.ws());
    }

    @PostMapping("/accounts")
    @PreAuthorize(Roles.OWNER)
    public Map<String, Object> connect(@RequestBody SocialService.AccountRequest r) {
        return svc.connect(Auth.ws(), r);
    }

    @DeleteMapping("/accounts/{id}")
    @PreAuthorize(Roles.OWNER)
    public Map<String, Object> disconnect(@PathVariable long id) {
        svc.disconnect(Auth.ws(), id);
        return Map.of("ok", true);
    }

    @GetMapping("/posts")
    @PreAuthorize(Roles.MANAGER)
    public List<Map<String, Object>> calendar(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        LocalDate f = from == null ? LocalDate.now(Time.IST).withDayOfMonth(1) : LocalDate.parse(from);
        LocalDate t = to == null ? f.plusMonths(1) : LocalDate.parse(to);
        return svc.calendar(Auth.ws(), Time.startOfIstDayUtc(f), Time.startOfIstDayUtc(t));
    }

    @GetMapping("/posts/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> get(@PathVariable long id) {
        return svc.get(Auth.ws(), id);
    }

    @PostMapping("/posts")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> create(@RequestBody SocialService.PostRequest r) {
        return svc.create(Auth.user(), r);
    }

    @PostMapping("/posts/{id}/cancel")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> cancel(@PathVariable long id) {
        return svc.cancel(Auth.ws(), id);
    }
}
