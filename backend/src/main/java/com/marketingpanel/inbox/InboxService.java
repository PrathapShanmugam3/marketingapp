package com.marketingpanel.inbox;

import com.marketingpanel.common.*;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/** Conversations and messages for the shared inbox (P16). The free reply window is 24 h after the customer's last message. */
@Service
public class InboxService {
    private final Db db;

    public InboxService(Db db) {
        this.db = db;
    }

    public long conversationId(long ws, long contactId) {
        db.update("insert ignore into conversations (workspace_id, contact_id) values (:ws,:c)", "ws", ws, "c", contactId);
        return db.scalar("select id from conversations where workspace_id=:ws and contact_id=:c", Long.class, "ws", ws, "c", contactId);
    }

    /** Stores an incoming message. Returns false for a duplicate wamid. */
    public boolean recordInbound(long ws, long contactId, String wamid, String type, String body, String mediaUrl, LocalDateTime at) {
        long conv = conversationId(ws, contactId);
        int n = db.update("insert ignore into messages (conversation_id, workspace_id, direction, wa_message_id, type, body, media_url, status, created_at) " +
                        "values (:cv,:ws,'IN',:w,:t,:b,:m,'RECEIVED',:at)", "cv", conv, "ws", ws, "w", wamid, "t", type, "b", body, "m", mediaUrl, "at", at);
        if (n == 0) return false;
        String preview = body == null ? "[" + type.toLowerCase() + "]" : (body.length() > 200 ? body.substring(0, 200) : body);
        db.update("update conversations set last_inbound_at=:at, last_message_preview=:p, unread_count=unread_count+1, status='OPEN' where id=:id",
                "at", at, "p", preview, "id", conv);
        db.update("update contacts set last_message_at=:at where id=:id", "at", at, "id", contactId);
        return true;
    }

    public long recordOutbound(long ws, long contactId, String wamid, String type, String body, String mediaUrl, Long userId, Long templateId, String category) {
        long conv = conversationId(ws, contactId);
        long id = db.insertInto("messages", "conversation_id", conv, "workspace_id", ws, "direction", "OUT", "wa_message_id", wamid, "type", type, "body", body,
                "media_url", mediaUrl, "status", "SENT", "sent_by", userId, "template_id", templateId, "category", category);
        String preview = body == null ? "[" + type.toLowerCase() + "]" : (body.length() > 200 ? body.substring(0, 200) : body);
        db.update("update conversations set last_message_preview=:p where id=:id", "p", preview, "id", conv);
        db.update("update contacts set last_message_at=:now where id=:id", "now", Time.now(), "id", contactId);
        return id;
    }

    public boolean windowOpen(Row conv) {
        return conv.t("last_inbound_at") != null && conv.t("last_inbound_at").isAfter(Time.now().minusHours(24));
    }

    public List<Map<String, Object>> list(long ws, String status, boolean unreadOnly, Long assignedTo, String q) {
        StringBuilder sb = new StringBuilder("select cv.*, c.full_name, c.phone, c.opt_in_status, (cv.last_inbound_at is not null and cv.last_inbound_at > :win) as window_open " +
                "from conversations cv join contacts c on c.id=cv.contact_id where cv.workspace_id=:ws and c.deleted_at is null");
        List<Object> kv = new ArrayList<>(List.of("ws", ws, "win", Time.now().minusHours(24)));
        if (status != null && !status.isBlank()) {
            sb.append(" and cv.status=:st");
            kv.add("st");
            kv.add(status);
        }
        if (unreadOnly) sb.append(" and cv.unread_count>0");
        if (assignedTo != null) {
            sb.append(" and cv.assigned_to=:at");
            kv.add("at");
            kv.add(assignedTo);
        }
        if (q != null && !q.isBlank()) {
            if (q.trim().length() < 2) throw ApiException.invalid("q", "search.min", "Type at least 2 characters.");
            sb.append(" and (c.full_name like :q or c.phone like :q)");
            kv.add("q");
            kv.add("%" + q.trim() + "%");
        }
        return db.list(sb + " order by cv.updated_at desc limit 200", kv.toArray());
    }

    public Row load(long ws, long id) {
        return db.row("select * from conversations where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Conversation"));
    }

    public Map<String, Object> messages(long ws, long id, Long before, int size) {
        Row conv = load(ws, id);
        size = Math.min(Math.max(size, 1), 100);
        List<Row> rows = db.rows("select m.*, u.full_name as sender_name from messages m left join users u on u.id=m.sent_by where m.conversation_id=:c" +
                (before != null ? " and m.id < :b" : "") + " order by m.id desc limit " + (size + 1), "c", id, "b", before);
        boolean more = rows.size() > size;
        if (more) rows = rows.subList(0, size);
        List<Map<String, Object>> items = new ArrayList<>(Row.api(rows));
        Collections.reverse(items);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("hasMore", more);
        out.put("windowOpen", windowOpen(conv));
        out.put("windowClosesAt", conv.t("last_inbound_at") == null ? null : conv.t("last_inbound_at").plusHours(24).toString() + "Z");
        return out;
    }

    public void markRead(long ws, long id) {
        load(ws, id);
        db.update("update conversations set unread_count=0 where id=:id", "id", id);
    }

    public Map<String, Object> patch(long ws, long id, Long assignedTo, boolean clearAssignee, String status) {
        load(ws, id);
        if (assignedTo != null && !db.exists("select 1 from workspace_members where workspace_id=:ws and user_id=:u and invite_status='ACCEPTED'", "ws", ws, "u", assignedTo))
            throw ApiException.invalid("assignedTo", "inbox.assignee", "Choose an active team member.");
        Map<String, Object> cols = new LinkedHashMap<>();
        if (assignedTo != null) cols.put("assigned_to", assignedTo);
        if (clearAssignee) cols.put("assigned_to", null);
        if (status != null) {
            if (!List.of("OPEN", "CLOSED").contains(status)) throw ApiException.badRequest("Unknown status.");
            cols.put("status", status);
        }
        db.updateTable("conversations", "id", id, cols, "workspace_id", ws);
        return db.one("select * from conversations where id=:id", "id", id).orElseThrow();
    }
}
