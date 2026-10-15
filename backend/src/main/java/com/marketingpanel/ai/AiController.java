package com.marketingpanel.ai;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai")
public class AiController {
    private final AiService svc;

    public AiController(AiService svc) {
        this.svc = svc;
    }

    @PostMapping("/generate")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> generate(@RequestBody AiService.GenerateRequest r) {
        return svc.generate(Auth.ws(), Auth.userId(), r);
    }

    @GetMapping("/usage")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> usage() {
        return svc.usage(Auth.ws());
    }

    @GetMapping("/history")
    @PreAuthorize(Roles.MANAGER)
    public List<Map<String, Object>> history() {
        return svc.history(Auth.ws());
    }
}
