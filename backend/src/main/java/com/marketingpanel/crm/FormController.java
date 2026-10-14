package com.marketingpanel.crm;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import com.marketingpanel.common.ApiException;
import com.marketingpanel.common.ClientInfo;
import com.marketingpanel.common.Html;
import com.marketingpanel.common.Row;
import com.marketingpanel.config.Settings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class FormController {
    private final FormService svc;
    private final Settings settings;

    public FormController(FormService svc, Settings settings) {
        this.svc = svc;
        this.settings = settings;
    }

    @GetMapping("/api/v1/forms")
    @PreAuthorize(Roles.MANAGER)
    public List<Map<String, Object>> list() {
        return svc.list(Auth.ws());
    }

    @GetMapping("/api/v1/forms/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> get(@PathVariable long id) {
        return svc.get(Auth.ws(), id);
    }

    @PostMapping("/api/v1/forms")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> create(@Valid @RequestBody CrmDtos.FormRequest r) {
        return svc.create(Auth.ws(), r);
    }

    @PutMapping("/api/v1/forms/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> update(@PathVariable long id, @Valid @RequestBody CrmDtos.FormRequest r) {
        return svc.update(Auth.ws(), id, r);
    }

    @DeleteMapping("/api/v1/forms/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> delete(@PathVariable long id) {
        svc.delete(Auth.ws(), id);
        return Map.of("ok", true);
    }

    /** Public form page (embeddable: no X-Frame-Options on /f/**). */
    @GetMapping(value = "/f/{slug}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page(@PathVariable String slug) {
        Row f = svc.bySlug(slug).orElseThrow(() -> ApiException.notFound("Form"));
        return ResponseEntity.ok().header("Content-Security-Policy",
                        "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; frame-ancestors *")
                .body(Html.formPage(f, settings.captchaSecret.isBlank()));
    }

    @PostMapping("/f/{slug}")
    public Map<String, Object> submit(@PathVariable String slug, @RequestBody FormService.Submission s, HttpServletRequest req) {
        String msg = svc.submit(slug, s, ClientInfo.ip(req));
        return Map.of("ok", true, "message", msg);
    }
}
