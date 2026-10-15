package com.marketingpanel.analytics;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/analytics")
@PreAuthorize(Roles.MANAGER)
public class AnalyticsController {
    private final AnalyticsService svc;

    public AnalyticsController(AnalyticsService svc) {
        this.svc = svc;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return svc.overview(Auth.ws(), from, to);
    }
}
