package com.marketingpanel.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.common.*;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Facebook Messenger and Instagram DM inbox. Messages arrive through the Meta webhook (/webhooks/social) and replies go out through the
 * same page token. Meta only allows replies within 24 hours of the customer's last message.
 */
@Service
public class DmService {
    private final Db db;
    private final Crypto crypto;
    private final SocialClient client;
    private final PlanService plans;
    private final NotificationService notifications;

    public DmService(Db db, Crypto crypto, SocialClient client, PlanService plans, NotificationService notifications) {
        this.db = db;
        this.crypto = crypto;
        this.client = client;
        this.plans = plans;
        this.notifications = notifications;
    }

    public List<Map<String, Object>> threads(long ws, String status, boolean unread) {
        plans.requireFeature(ws, "dm");
        return db.list("select t.*, a.platform, a.name as account_name, (t.last_inbound_at is not null and t.last_inbound_at > :win) as window_open from dm_threads t join social_accounts a on a.id=t.social_account_id " +
                "where t.workspace_id=:ws" + (status != null && !status.isBlank() ? " and t.status=:st" : "") + (unread ? " and t.unread_count>0" : "") + " order by t.updated_at desc limit 200",
                "ws", ws, "win", Time.now().minusHours(24), "st", status);
    }

    public Map<String, Object> messages(long ws, long threadId) {
        Row t = thread(ws, threadId);
        db.update("update dm_threads set unread_count=0 where id=:id", "id", threadId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("thread", t.api());
        m.put("windowOpen", t.t("last_inbound_at") != null && t.t("last_inbound_at").isAfter(Time.now().minusHours(24)));
        List<Map<String, Object>> items = new ArrayList<>(db.list("select m.*, u.full_name as sender_name from dm_messages m left join users u on u.id=m.sent_by where m.thread_id=:t order by m.id desc limit 100", "t", threadId));
        Collections.reverse(items);
        m.put("items", items);
        return m;
    }

    private Row thread(long ws, long id) {
        return db.row("select * from dm_threads where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Conversation"));
    }

    public Map<String, Object> reply(long ws, long userId, long threadId, String text) {
        plans.requireFeature(ws, "dm");
        Row t = thread(ws, threadId);
        String body = Norm.plain(text);
        if (body == null || body.isBlank()) throw ApiException.invalid("text", "dm.empty", "Message cannot be empty.");
        if (body.length() > 1000) throw ApiException.invalid("text", "vr.size", "Messages can be up to 1000 characters.");
        if (t.t("last_inbound_at") == null || t.t("last_inbound_at").isBefore(Time.now().minusHours(24)))
            throw ApiException.state("The 24-hour reply window has closed. The customer must message you first.");
        Row acct = db.row("select * from social_accounts where id=:id and status='CONNECTED'", "id", t.l("social_account_id")).orElseThrow(() -> ApiException.state("This account is disconnected."));
        String status = "SENT";
        String ext = null;
        try {
            ext = client.sendMessage(acct.s("platform"), acct.s("external_id"), crypto.decrypt(acct.bytes("access_token_enc")), t.s("platform_user_id"), body);
        } catch (SocialClient.SocialException e) {
            throw ApiException.gateway("The message could not be sent: " + e.getMessage());
        }
        long id = db.insertInto("dm_messages", "thread_id", threadId, "direction", "OUT", "body", body, "external_id", ext, "status", status, "sent_by", userId);
        db.update("update dm_threads set last_message_preview=:p where id=:id", "p", body.length() > 200 ? body.substring(0, 200) : body, "id", threadId);
        return messages(ws, threadId);
    }

    public Map<String, Object> setStatus(long ws, long threadId, String status) {
        thread(ws, threadId);
        if (!List.of("OPEN", "RESOLVED").contains(status)) throw ApiException.badRequest("Unknown status.");
        db.update("update dm_threads set status=:s where id=:id", "s", status, "id", threadId);
        return Map.of("ok", true);
    }

    /** Stores an inbound DM (idempotent on the platform message id). Returns false if the page is not connected to any workspace. */
    public boolean ingest(String platform, String pageId, String senderId, String senderName, String mid, String text) {
        Row acct = db.row("select * from social_accounts where platform=:p and external_id=:e and status='CONNECTED' limit 1", "p", platform, "e", pageId).orElse(null);
        if (acct == null) return false;
        long ws = acct.l("workspace_id");
        if (!plans.hasFeature(ws, "dm")) return false;
        String body = text == null ? "" : (text.length() > 1900 ? text.substring(0, 1900) : text);
        db.update("insert into dm_threads (workspace_id, social_account_id, platform_user_id, display_name, last_message_preview, last_inbound_at, unread_count, status) values (:ws,:a,:u,:n,:p,:now,1,'OPEN') " +
                        "on duplicate key update last_message_preview=:p, last_inbound_at=:now, unread_count=unread_count+1, status='OPEN', display_name=coalesce(:n, display_name)",
                "ws", ws, "a", acct.l("id"), "u", senderId, "n", senderName, "p", body.length() > 200 ? body.substring(0, 200) : body, "now", Time.now());
        long tid = db.scalar("select id from dm_threads where social_account_id=:a and platform_user_id=:u", Long.class, "a", acct.l("id"), "u", senderId);
        try {
            db.insertInto("dm_messages", "thread_id", tid, "direction", "IN", "body", body, "external_id", mid, "status", "RECEIVED");
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return true; // already stored
        }
        notifications.notifyOnce(ws, "DM_" + tid, 1, "New " + acct.s("platform").toLowerCase() + " message", (senderName == null ? "A customer" : senderName) + ": " + (body.length() > 80 ? body.substring(0, 80) : body), "/social/dm");
        return true;
    }

    /** Parses a Meta "page"/"instagram" webhook payload. */
    public int ingestWebhook(JsonNode root) {
        int n = 0;
        String object = root.path("object").asText();
        String platform = "instagram".equals(object) ? "INSTAGRAM" : "FACEBOOK";
        if (!List.of("page", "instagram").contains(object)) return 0;
        for (JsonNode entry : root.path("entry")) {
            String pageId = entry.path("id").asText();
            for (JsonNode m : entry.path("messaging")) {
                JsonNode msg = m.path("message");
                if (msg.isMissingNode() || msg.path("is_echo").asBoolean(false)) continue;
                String text = msg.path("text").asText(null);
                if (text == null && msg.path("attachments").size() > 0) text = "[attachment]";
                if (text == null) continue;
                if (ingest(platform, pageId, m.path("sender").path("id").asText(), null, msg.path("mid").asText(null), text)) n++;
            }
        }
        return n;
    }
}
