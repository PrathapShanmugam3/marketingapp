package com.marketingpanel.inbox;

import com.marketingpanel.campaign.FrequencyCap;
import com.marketingpanel.common.*;
import com.marketingpanel.whatsapp.RateService;
import com.marketingpanel.whatsapp.WaException;
import com.marketingpanel.whatsapp.WaGateway;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * One-off sends outside campaigns: form auto-replies, review requests, automation steps and template replies in the inbox.
 * Charges the wallet per message, records the message in the contact's inbox thread and refunds on failure.
 */
@Service
public class DirectSendService {
    private final Db db;
    private final WaGateway gw;
    private final WalletService wallet;
    private final RateService rates;
    private final InboxService inbox;
    private final FrequencyCap freq;

    public DirectSendService(Db db, WaGateway gw, WalletService wallet, RateService rates, InboxService inbox, FrequencyCap freq) {
        this.db = db;
        this.gw = gw;
        this.wallet = wallet;
        this.rates = rates;
        this.inbox = inbox;
        this.freq = freq;
    }

    public enum Skip {OPTED_OUT, FREQ_CAP}

    public record Result(String wamid, Skip skipped) {}

    public static String render(String body, List<String> params) {
        String out = body;
        for (int i = 0; i < params.size(); i++) out = out.replace("{{" + (i + 1) + "}}", params.get(i));
        return out;
    }

    /** Throws ApiException (business rule) or WaException (Meta); the caller decides about retries. */
    public Result sendTemplate(long ws, long contactId, long templateId, List<String> params, String headerMedia, List<String> urlSuffixes,
                               Long userId, boolean enforceFreqCap) {
        Row acct = gw.sendable(ws);
        Row tpl = db.row("select * from wa_templates where id=:id and workspace_id=:ws", "id", templateId, "ws", ws).orElseThrow(() -> ApiException.notFound("Template"));
        if (!"APPROVED".equals(tpl.s("status"))) throw ApiException.state("The template is not approved.");
        Row contact = db.row("select * from contacts where id=:id and workspace_id=:ws and deleted_at is null", "id", contactId, "ws", ws).orElseThrow(() -> ApiException.notFound("Contact"));
        String category = tpl.s("category");
        boolean marketing = "MARKETING".equals(category);
        if ("OPTED_OUT".equals(contact.s("opt_in_status")) || (marketing && !"OPTED_IN".equals(contact.s("opt_in_status")))) return new Result(null, Skip.OPTED_OUT);
        if (marketing && enforceFreqCap && freq.exceeded(ws, contactId)) return new Result(null, Skip.FREQ_CAP);
        BigDecimal rate = rates.sellRate(category, contact.s("phone"));
        wallet.debit(ws, rate, "MANUAL", contactId, "Template message " + tpl.s("name"), userId);
        try {
            String wamid = gw.sendTemplate(acct, WaGateway.build(tpl, params, headerMedia, urlSuffixes), contact.s("phone"));
            inbox.recordOutbound(ws, contactId, wamid, "TEMPLATE", render(tpl.s("body"), params), null, userId, templateId, category);
            return new Result(wamid, null);
        } catch (WaException e) {
            wallet.refund(ws, rate, "MANUAL", contactId, "Refund: template message failed");
            throw e;
        }
    }

    /** Free-form reply inside the 24-hour window. */
    public String sendText(long ws, long conversationId, String text, String mediaUrl, String mediaType, long userId) {
        Row conv = inbox.load(ws, conversationId);
        if (!inbox.windowOpen(conv)) throw ApiException.state("The 24-hour window has closed. Send a template instead.");
        Row acct = gw.connected(ws);
        Row contact = db.row("select * from contacts where id=:id and workspace_id=:ws and deleted_at is null", "id", conv.l("contact_id"), "ws", ws)
                .orElseThrow(() -> ApiException.notFound("Contact"));
        try {
            String wamid = mediaUrl != null ? gw.sendMedia(acct, contact.s("phone"), mediaType, mediaUrl, text)
                    : gw.sendText(acct, contact.s("phone"), text);
            inbox.recordOutbound(ws, contact.l("id"), wamid, mediaUrl != null ? ("image".equals(mediaType) ? "IMAGE" : "DOCUMENT") : "TEXT", text, mediaUrl, userId, null, null);
            return wamid;
        } catch (WaException e) {
            throw ApiException.gateway("The message could not be sent: " + e.getMessage());
        }
    }

    public static Map<String, Object> ok() {
        return Map.of("ok", true);
    }
}
