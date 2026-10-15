package com.marketingpanel.ads;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ads")
@PreAuthorize(Roles.MANAGER)
public class AdsController {
    private final AdsService svc;

    public AdsController(AdsService svc) {
        this.svc = svc;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return svc.list(Auth.ws());
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody AdsService.Request r) {
        return svc.create(Auth.ws(), r);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return svc.get(Auth.ws(), id);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody AdsService.Request r) {
        return svc.update(Auth.ws(), id, r);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        svc.delete(Auth.ws(), id);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/metrics")
    public Map<String, Object> metric(@PathVariable long id, @RequestBody AdsService.Metric m) {
        return svc.addMetric(Auth.ws(), id, m);
    }

    @PostMapping("/{id}/import")
    public Map<String, Object> importCsv(@PathVariable long id, @RequestParam("file") MultipartFile file) throws IOException {
        return svc.importCsv(Auth.ws(), id, file.getBytes());
    }

    @PostMapping("/{id}/sync")
    public Map<String, Object> sync(@PathVariable long id) {
        return svc.sync(Auth.ws(), id);
    }
}
