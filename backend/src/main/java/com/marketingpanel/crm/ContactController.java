package com.marketingpanel.crm;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/contacts")
public class ContactController {
    private final ContactService svc;

    public ContactController(ContactService svc) {
        this.svc = svc;
    }

    @GetMapping
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> list(@RequestParam(required = false) String q, @RequestParam(required = false) List<Long> tagIds,
                                    @RequestParam(required = false) String optIn, @RequestParam(required = false) String city,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                    @RequestParam(required = false) Long cursor, @RequestParam(defaultValue = "25") int size) {
        return svc.list(Auth.ws(), new ContactService.Filter(q, tagIds, optIn, city, from, to), cursor, size);
    }

    @PostMapping
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> create(@Valid @RequestBody CrmDtos.ContactRequest r) {
        return svc.create(Auth.ws(), Auth.userId(), r);
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> get(@PathVariable long id) {
        return svc.get(Auth.ws(), id);
    }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> update(@PathVariable long id, @Valid @RequestBody CrmDtos.ContactRequest r) {
        return svc.update(Auth.ws(), id, r);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> delete(@PathVariable long id) {
        svc.delete(Auth.ws(), id);
        return Map.of("ok", true);
    }

    @PostMapping("/bulk")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> bulk(@Valid @RequestBody CrmDtos.BulkRequest r) {
        Map<String, Object> f = r.filter() == null ? Map.of() : r.filter();
        ContactService.Filter filter = new ContactService.Filter((String) f.get("q"), null, (String) f.get("optIn"), (String) f.get("city"), null, null);
        return svc.bulk(Auth.ws(), r, filter);
    }

    @GetMapping("/export")
    @PreAuthorize(Roles.MANAGER)
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String q, @RequestParam(required = false) String optIn) {
        String csv = svc.exportCsv(Auth.ws(), new ContactService.Filter(q, null, optIn, null, null, null));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=contacts.csv")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8")).body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
