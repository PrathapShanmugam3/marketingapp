package com.marketingpanel.crm;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/leads")
@PreAuthorize(Roles.MEMBER)
public class LeadController {
    private final LeadService svc;

    public LeadController(LeadService svc) {
        this.svc = svc;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String status, @RequestParam(required = false) Long assignedTo,
                                    @RequestParam(required = false) String q) {
        return svc.list(Auth.user(), status, assignedTo, q);
    }

    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody CrmDtos.LeadRequest r) {
        return svc.create(Auth.user(), r);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return svc.get(Auth.user(), id);
    }

    @PatchMapping("/{id}")
    public Map<String, Object> patch(@PathVariable long id, @Valid @RequestBody CrmDtos.LeadPatch r) {
        return svc.patch(Auth.user(), id, r);
    }

    @PostMapping("/{id}/notes")
    public Map<String, Object> note(@PathVariable long id, @Valid @RequestBody CrmDtos.NoteRequest r) {
        return svc.addNote(Auth.user(), id, r);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> delete(@PathVariable long id) {
        svc.delete(Auth.user(), id);
        return Map.of("ok", true);
    }
}
