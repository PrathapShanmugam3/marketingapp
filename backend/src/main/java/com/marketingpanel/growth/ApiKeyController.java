package com.marketingpanel.growth;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import com.marketingpanel.common.*;
import com.marketingpanel.crm.ContactService;
import com.marketingpanel.crm.CrmDtos;
import com.marketingpanel.crm.LeadService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class ApiKeyController {
    private final ApiKeyService keys;
    private final ContactService contacts;
    private final LeadService leads;
    private final LinkService links;
    private final AutomationEngine engine;
    private final Db db;

    public ApiKeyController(ApiKeyService keys, ContactService contacts, LeadService leads, LinkService links, AutomationEngine engine, Db db) {
        this.keys = keys;
        this.contacts = contacts;
        this.leads = leads;
        this.links = links;
        this.engine = engine;
        this.db = db;
    }

    @GetMapping("/api/v1/api-keys")
    @PreAuthorize(Roles.OWNER)
    public List<Map<String, Object>> list() {
        return keys.list(Auth.ws());
    }

    @PostMapping("/api/v1/api-keys")
    @PreAuthorize(Roles.OWNER)
    public Map<String, Object> create(@RequestBody ApiKeyService.KeyRequest r) {
        return keys.create(Auth.ws(), Auth.userId(), r);
    }

    @DeleteMapping("/api/v1/api-keys/{id}")
    @PreAuthorize(Roles.OWNER)
    public Map<String, Object> revoke(@PathVariable long id) {
        keys.revoke(Auth.ws(), id);
        return Map.of("ok", true);
    }

    // ----- integration endpoints used by POS systems and websites (X-API-Key header) -----

    @PostMapping("/api/v1/integrations/contacts")
    public Map<String, Object> addContact(@RequestHeader(name = "X-API-Key", required = false) String key, @RequestHeader(name = "Authorization", required = false) String auth,
                                          @Valid @RequestBody CrmDtos.ContactRequest r) {
        var k = keys.authenticate(key, auth, "contacts:write");
        return contacts.create(k.workspaceId(), null, r);
    }

    @GetMapping("/api/v1/integrations/contacts")
    public Map<String, Object> findContact(@RequestHeader(name = "X-API-Key", required = false) String key, @RequestHeader(name = "Authorization", required = false) String auth,
                                           @RequestParam String phone) {
        var k = keys.authenticate(key, auth, "contacts:read");
        String p = Phones.e164(phone);
        if (p == null) throw ApiException.invalid("phone", "vr.mobile.invalid", "Enter a valid 10-digit mobile number.");
        Row c = db.row("select id from contacts where workspace_id=:ws and phone=:p and deleted_at is null", "ws", k.workspaceId(), "p", p).orElseThrow(() -> ApiException.notFound("Contact"));
        return contacts.get(k.workspaceId(), c.l("id"));
    }

    public record LeadIn(@jakarta.validation.constraints.NotBlank String phone, String fullName, String requirement, String source) {}

    @PostMapping("/api/v1/integrations/leads")
    public Map<String, Object> addLead(@RequestHeader(name = "X-API-Key", required = false) String key, @RequestHeader(name = "Authorization", required = false) String auth,
                                       @Valid @RequestBody LeadIn r) {
        var k = keys.authenticate(key, auth, "leads:write");
        String p = Phones.e164(r.phone());
        if (p == null) throw ApiException.invalid("phone", "vr.mobile.invalid", "Enter a valid 10-digit mobile number.");
        long ws = k.workspaceId();
        Row c = db.row("select id from contacts where workspace_id=:ws and phone=:p and deleted_at is null", "ws", ws, "p", p).orElse(null);
        long cid;
        if (c != null) cid = c.l("id");
        else {
            String name = Norm.name(r.fullName() == null ? "Lead " + p.substring(p.length() - 4) : r.fullName());
            cid = ((Number) contacts.create(ws, null, new CrmDtos.ContactRequest(name, p, null, null, null, null, null, false, null, null)).get("id")).longValue();
        }
        String src = r.source() != null && List.of("FORM", "WHATSAPP", "CALL", "WALK_IN", "INSTAGRAM", "FACEBOOK", "REFERRAL", "OTHER").contains(r.source()) ? r.source() : "OTHER";
        long id = db.insertInto("leads", "workspace_id", ws, "contact_id", cid, "source", src, "status", "NEW", "requirement", Norm.plain(r.requirement()));
        leads.addActivity(id, "STATUS_CHANGE", "Lead created through the API", null);
        return Map.of("id", id, "contactId", cid);
    }

    public record TriggerIn(Long automationId, String phone) {}

    @PostMapping("/api/v1/integrations/automations/trigger")
    public Map<String, Object> trigger(@RequestHeader(name = "X-API-Key", required = false) String key, @RequestHeader(name = "Authorization", required = false) String auth,
                                       @RequestBody TriggerIn r) {
        var k = keys.authenticate(key, auth, "automations:trigger");
        String p = Phones.e164(r.phone());
        if (p == null || r.automationId() == null) throw ApiException.badRequest("automationId and a valid phone are required.");
        Row c = db.row("select id from contacts where workspace_id=:ws and phone=:p and deleted_at is null", "ws", k.workspaceId(), "p", p).orElseThrow(() -> ApiException.notFound("Contact"));
        boolean started = engine.startManual(k.workspaceId(), r.automationId(), c.l("id"));
        return Map.of("started", started);
    }

    public record LinkIn(String destinationUrl, String utmSource, String utmMedium, String utmCampaign) {}

    @PostMapping("/api/v1/integrations/links")
    public Map<String, Object> link(@RequestHeader(name = "X-API-Key", required = false) String key, @RequestHeader(name = "Authorization", required = false) String auth,
                                    @RequestBody LinkIn r) {
        var k = keys.authenticate(key, auth, "links:write");
        return links.create(k.workspaceId(), null, new LinkService.LinkRequest("URL", r.destinationUrl(), null, null, null, r.utmSource(), r.utmMedium(), r.utmCampaign(), null));
    }
}
