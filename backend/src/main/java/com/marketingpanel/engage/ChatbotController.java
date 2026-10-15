package com.marketingpanel.engage;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/chatbot")
@PreAuthorize(Roles.MANAGER)
public class ChatbotController {
    private final ChatbotService svc;

    public ChatbotController(ChatbotService svc) {
        this.svc = svc;
    }

    @GetMapping("/rules")
    public List<Map<String, Object>> list() {
        return svc.list(Auth.ws());
    }

    @PostMapping("/rules")
    public Map<String, Object> create(@RequestBody ChatbotService.RuleRequest r) {
        return svc.create(Auth.ws(), r);
    }

    @PutMapping("/rules/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody ChatbotService.RuleRequest r) {
        return svc.update(Auth.ws(), id, r);
    }

    @DeleteMapping("/rules/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        svc.delete(Auth.ws(), id);
        return Map.of("ok", true);
    }

    public record Try(String text, boolean firstMessage) {}

    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody Try t) {
        return svc.test(Auth.ws(), t.text(), t.firstMessage());
    }
}
