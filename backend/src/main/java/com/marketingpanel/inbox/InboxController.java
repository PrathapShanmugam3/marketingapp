package com.marketingpanel.inbox;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import com.marketingpanel.common.*;
import com.marketingpanel.whatsapp.WaException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/inbox")
@PreAuthorize(Roles.MEMBER)
public class InboxController {
    private final InboxService inbox;
    private final DirectSendService send;
    private final Db db;

    public InboxController(InboxService inbox, DirectSendService send, Db db) {
        this.inbox = inbox;
        this.send = send;
        this.db = db;
    }

    public record Reply(@Size(max = 4096, message = "{vr.size}") String text, String attachmentUrl, Long templateId, List<String> variables) {}

    public record Patch(Long assignedTo, Boolean clearAssignee, String status) {}

    @GetMapping("/conversations")
    public List<Map<String, Object>> list(@RequestParam(required = false) String status, @RequestParam(defaultValue = "false") boolean unread,
                                          @RequestParam(required = false) Long assignedTo, @RequestParam(required = false) String q) {
        return inbox.list(Auth.ws(), status, unread, assignedTo, q);
    }

    @GetMapping("/conversations/{id}/messages")
    public Map<String, Object> messages(@PathVariable long id, @RequestParam(required = false) Long before, @RequestParam(defaultValue = "50") int size) {
        return inbox.messages(Auth.ws(), id, before, size);
    }

    @PostMapping("/conversations/{id}/read")
    public Map<String, Object> read(@PathVariable long id) {
        inbox.markRead(Auth.ws(), id);
        return Map.of("ok", true);
    }

    @PatchMapping("/conversations/{id}")
    public Map<String, Object> patch(@PathVariable long id, @RequestBody Patch p) {
        return inbox.patch(Auth.ws(), id, p.assignedTo(), Boolean.TRUE.equals(p.clearAssignee()), p.status());
    }

    @PostMapping("/conversations/{id}/messages")
    public Map<String, Object> reply(@PathVariable long id, @Valid @RequestBody Reply r) {
        long ws = Auth.ws();
        com.marketingpanel.common.Row conv = inbox.load(ws, id);
        if (r.templateId() != null) { // outside the window: approved template + variables
            try {
                var res = send.sendTemplate(ws, conv.l("contact_id"), r.templateId(), r.variables() == null ? List.of() : r.variables(), null, null, Auth.userId(), false);
                if (res.skipped() != null) throw ApiException.state("This customer has opted out of messages.");
            } catch (WaException e) {
                throw ApiException.gateway("The message could not be sent: " + e.getMessage());
            }
            return inbox.messages(ws, id, null, 50);
        }
        String text = Norm.plain(r.text());
        boolean hasMedia = r.attachmentUrl() != null && !r.attachmentUrl().isBlank();
        if ((text == null || text.isBlank()) && !hasMedia) throw ApiException.invalid("text", "inbox.empty", "Message cannot be empty.");
        if (text != null && Norm.hasControlChars(text)) throw ApiException.invalid("text", "vr.text.unsupported", "Remove unsupported characters.");
        String type = hasMedia && r.attachmentUrl().endsWith(".pdf") ? "document" : "image";
        send.sendText(ws, id, text == null || text.isBlank() ? null : text, hasMedia ? r.attachmentUrl() : null, type, Auth.userId());
        return inbox.messages(ws, id, null, 50);
    }
}
