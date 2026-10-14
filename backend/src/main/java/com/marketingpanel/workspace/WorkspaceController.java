package com.marketingpanel.workspace;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import com.marketingpanel.common.*;
import com.marketingpanel.common.validation.PersonName;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class WorkspaceController {
    private final WorkspaceService svc;
    private final NotificationService notifications;

    public WorkspaceController(WorkspaceService svc, NotificationService notifications) {
        this.svc = svc;
        this.notifications = notifications;
    }

    @GetMapping("/workspace")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> get() {
        return svc.get(Auth.ws());
    }

    @PutMapping("/workspace")
    @PreAuthorize(Roles.OWNER)
    public Map<String, Object> update(@Valid @RequestBody WorkspaceDtos.UpdateWorkspace r) {
        return svc.update(Auth.ws(), r);
    }

    @GetMapping("/team")
    @PreAuthorize(Roles.OWNER)
    public Map<String, Object> team() {
        return svc.team(Auth.ws());
    }

    @PostMapping("/team")
    @PreAuthorize(Roles.OWNER)
    public Map<String, Object> invite(@Valid @RequestBody WorkspaceDtos.InviteRequest r) {
        return svc.invite(Auth.ws(), r);
    }

    @PostMapping("/team/{id}/resend")
    @PreAuthorize(Roles.OWNER)
    public Map<String, Object> resend(@PathVariable long id) {
        svc.resend(Auth.ws(), id);
        return Map.of("ok", true);
    }

    @PatchMapping("/team/{id}")
    @PreAuthorize(Roles.OWNER)
    public Map<String, Object> role(@PathVariable long id, @Valid @RequestBody WorkspaceDtos.RoleChange r) {
        svc.changeRole(Auth.ws(), id, r.role());
        return Map.of("ok", true);
    }

    @DeleteMapping("/team/{id}")
    @PreAuthorize(Roles.OWNER)
    public Map<String, Object> remove(@PathVariable long id) {
        svc.remove(Auth.ws(), id);
        return Map.of("ok", true);
    }

    // P24 agency client workspaces
    public record ClientRequest(@NotBlank(message = "{vr.required}") @PersonName(digits = true) String name,
                                @NotBlank(message = "{vr.required}") @Pattern(regexp = "^[\\p{L}\\p{M} .'-]{2,60}$", message = "{vr.name.invalid}") String city,
                                @NotBlank(message = "{vr.required}") String state, String logoUrl,
                                @Pattern(regexp = "^$|^#[0-9a-fA-F]{6}$", message = "{vr.required}") String brandColor) {}

    @GetMapping("/agency/clients")
    @PreAuthorize(Roles.AGENCY)
    public List<Map<String, Object>> clients() {
        return svc.clients(Auth.ws());
    }

    @PostMapping("/agency/clients")
    @PreAuthorize(Roles.AGENCY)
    public Map<String, Object> createClient(@Valid @RequestBody ClientRequest r) {
        return svc.createClient(Auth.ws(), Auth.userId(), Norm.name(r.name()), Norm.name(r.city()), r.state(), r.logoUrl(), r.brandColor());
    }

    // notifications
    @GetMapping("/notifications")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> notifications(@RequestParam(defaultValue = "false") boolean unreadOnly) {
        return Map.of("items", notifications.list(Auth.ws(), Auth.userId(), unreadOnly, 50), "unread", notifications.unread(Auth.ws(), Auth.userId()));
    }

    @PostMapping("/notifications/read")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> read(@RequestParam(required = false) Long id) {
        notifications.markRead(Auth.ws(), Auth.userId(), id);
        return Map.of("ok", true);
    }
}
