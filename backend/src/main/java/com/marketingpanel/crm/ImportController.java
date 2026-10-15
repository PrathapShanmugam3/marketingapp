package com.marketingpanel.crm;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import com.marketingpanel.common.ApiException;
import com.marketingpanel.common.Db;
import com.marketingpanel.common.FileStore;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/contacts/imports")
@PreAuthorize(Roles.MANAGER)
public class ImportController {
    private final ImportService svc;

    public ImportController(ImportService svc) {
        this.svc = svc;
    }

    @PostMapping
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        return svc.upload(Auth.ws(), Auth.userId(), file.getOriginalFilename(), file.getBytes());
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return svc.list(Auth.ws());
    }

    public record PreviewRequest(com.fasterxml.jackson.databind.JsonNode mapping, String defaultCountryCode) {}

    @PostMapping("/{id}/preview")
    public Map<String, Object> preview(@PathVariable long id, @RequestBody PreviewRequest r) {
        return svc.preview(Auth.ws(), id, r.mapping(), r.defaultCountryCode());
    }

    @PostMapping("/{id}/start")
    public Map<String, Object> start(@PathVariable long id, @RequestBody ImportService.StartRequest r) {
        return svc.start(Auth.ws(), id, r);
    }

    @GetMapping("/{id}")
    public Map<String, Object> status(@PathVariable long id) {
        return svc.status(Auth.ws(), id);
    }
}
