package com.marketingpanel.whatsapp;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.JwtAuthFilter;
import com.marketingpanel.auth.Roles;
import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** P12 WhatsApp Connect: one connected number per workspace, via Meta Embedded Signup. */
@RestController
@RequestMapping("/api/v1/whatsapp")
public class WhatsAppController {
    private final Db db;
    private final Crypto crypto;
    private final WaGateway gw;
    private final Settings settings;
    private final AuditService audit;
    private final NotificationService notifications;

    public WhatsAppController(Db db, Crypto crypto, WaGateway gw, Settings settings, AuditService audit, NotificationService notifications) {
        this.db = db;
        this.crypto = crypto;
        this.gw = gw;
        this.settings = settings;
        this.audit = audit;
        this.notifications = notifications;
    }

    public record ConnectRequest(@NotBlank String code, @NotBlank String wabaId, @NotBlank String phoneNumberId) {}

    public record DisconnectRequest(String confirm) {}

    @GetMapping("/account")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> account() {
        Row a = gw.account(Auth.ws());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connected", a != null && "CONNECTED".equals(a.s("status")));
        out.put("account", a == null ? null : db.one("select id, waba_id, phone_number_id, display_phone, verified_name, quality_rating, messaging_limit, status, connected_at " +
                "from wa_accounts where id=:id", "id", a.l("id")).orElse(null));
        out.put("metaAppId", settings.metaAppId);
        out.put("mock", settings.whatsappMock);
        return out;
    }

    @PostMapping("/connect")
    @PreAuthorize(Roles.OWNER)
    public Map<String, Object> connect(@Valid @RequestBody ConnectRequest r) {
        long ws = Auth.ws();
        String token;
        WhatsAppClient.PhoneInfo info;
        try {
            token = gw.client().exchangeCode(r.code());
            info = gw.client().fetchPhone(token, r.phoneNumberId());
            gw.client().subscribeApp(token, r.wabaId());
        } catch (WaException e) {
            throw new ApiException(ErrorCode.GATEWAY_ERROR, "Connection was cancelled. Please try again.");
        }
        if (db.exists("select 1 from wa_accounts where phone_number_id=:p and workspace_id<>:ws", "p", r.phoneNumberId(), "ws", ws))
            throw ApiException.duplicate("This WhatsApp number is already connected to another workspace.");
        byte[] enc = crypto.encrypt(token);
        Row existing = gw.account(ws);
        if (existing != null) { // reconnect replaces tokens, keeps templates
            db.update("update wa_accounts set waba_id=:w, phone_number_id=:p, display_phone=:d, verified_name=:v, access_token_enc=:t, quality_rating=:q, " +
                            "messaging_limit=:l, status='CONNECTED', connected_at=:now where id=:id",
                    "w", r.wabaId(), "p", r.phoneNumberId(), "d", info.displayPhone(), "v", info.verifiedName(), "t", enc, "q", info.qualityRating(),
                    "l", info.messagingLimit(), "now", Time.now(), "id", existing.l("id"));
        } else {
            db.insertInto("wa_accounts", "workspace_id", ws, "waba_id", r.wabaId(), "phone_number_id", r.phoneNumberId(), "display_phone", info.displayPhone(),
                    "verified_name", info.verifiedName(), "access_token_enc", enc, "quality_rating", info.qualityRating(), "messaging_limit", info.messagingLimit(),
                    "status", "CONNECTED", "connected_at", Time.now());
        }
        audit.log("WHATSAPP_CONNECTED", "wa_accounts", null, null, Map.of("phone", info.displayPhone()));
        return account();
    }

    @DeleteMapping("/account")
    @PreAuthorize(Roles.OWNER)
    public Map<String, Object> disconnect(@RequestBody DisconnectRequest r) {
        long ws = Auth.ws();
        if (r == null || !"DISCONNECT".equals(r.confirm())) throw ApiException.invalid("confirm", "wa.confirm", "Type DISCONNECT to confirm.");
        Row a = gw.account(ws);
        if (a == null) throw ApiException.notFound("WhatsApp account");
        db.update("update wa_accounts set status='DISCONNECTED' where id=:id", "id", a.l("id"));
        int paused = db.update("update campaigns set status='PAUSED', paused_by_system=1 where workspace_id=:ws and status in ('SCHEDULED','QUEUED','SENDING')", "ws", ws);
        notifications.notifyOwners(ws, "WHATSAPP_DISCONNECTED", "WhatsApp disconnected", paused + " campaign(s) were paused.", "/settings/whatsapp");
        audit.log("WHATSAPP_DISCONNECTED", "wa_accounts", a.l("id"));
        return account();
    }
}
