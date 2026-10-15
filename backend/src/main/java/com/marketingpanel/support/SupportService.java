package com.marketingpanel.support;

import com.marketingpanel.auth.AuthUser;
import com.marketingpanel.common.*;
import org.springframework.stereotype.Service;

import java.util.*;

/** P39 Support tickets for businesses and promoters; staff reply and resolve. */
@Service
public class SupportService {
    private final Db db;
    private final AuditService audit;

    public SupportService(Db db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    public record TicketRequest(String category, String subject, String message, String referenceType, Long referenceId, List<String> attachments, String priority) {}

    public record MessageRequest(String body, Boolean internal, List<String> attachments) {}

    public record Patch(String status, String priority, Long assignedTo) {}

    private static List<String> files(List<String> in) {
        List<String> out = in == null ? List.of() : in.stream().filter(s -> s != null && !s.isBlank()).toList();
        if (out.size() > 3) throw ApiException.invalid("attachments", "ticket.attach", "Attach up to 3 files under 5 MB.");
        for (String u : out) if (u.length() > 500 || !u.contains("/files/")) throw ApiException.invalid("attachments", "ticket.attach", "Attach up to 3 files under 5 MB.");
        return out;
    }

    private void checkReference(AuthUser me, String type, Long id) {
        if (type == null || id == null) return;
        boolean ok = switch (type) {
            case "PAYOUT" -> db.exists("select 1 from payouts po join promoters p on p.id=po.promoter_id where po.id=:id and p.user_id=:u", "id", id, "u", me.userId());
            case "PAYMENT" -> me.workspaceId() != null && db.exists("select 1 from payments where id=:id and workspace_id=:ws", "id", id, "ws", me.workspaceId());
            case "CAMPAIGN" -> me.workspaceId() != null && db.exists("select 1 from campaigns where id=:id and workspace_id=:ws", "id", id, "ws", me.workspaceId());
            default -> false;
        };
        if (!ok) throw ApiException.invalid("referenceId", "ticket.reference", "Pick a record from your own account.");
    }

    public Map<String, Object> create(AuthUser me, TicketRequest r) {
        List<FieldErr> errs = new ArrayList<>();
        if (r.category() == null || !List.of("BILLING", "PAYOUT", "WHATSAPP", "TRACKING", "ACCOUNT", "OTHER").contains(r.category())) errs.add(new FieldErr("category", "ticket.category", "Choose a category."));
        String subject = Norm.plain(Norm.name(r.subject()));
        if (subject == null || subject.length() < 5 || subject.length() > 120) errs.add(new FieldErr("subject", "ticket.subject", "Enter a short subject."));
        String body = Norm.plain(r.message());
        if (body == null || body.length() < 20 || body.length() > 5000) errs.add(new FieldErr("message", "ticket.message", "Please describe the issue (at least 20 characters)."));
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        if (db.count("select count(*) from support_tickets where user_id=:u and status in ('OPEN','WAITING_CUSTOMER')", "u", me.userId()) >= 5)
            throw ApiException.state("You have 5 open tickets. Wait for a reply or close one first.");
        checkReference(me, r.referenceType(), r.referenceId());
        List<String> attachments = files(r.attachments());
        String priority = "PAYOUT".equals(r.category()) || "BILLING".equals(r.category()) ? "HIGH" : "NORMAL";
        long id = db.tx(() -> {
            long t = db.insertInto("support_tickets", "workspace_id", me.workspaceId(), "user_id", me.userId(), "category", r.category(), "subject", subject, "priority", priority,
                    "reference_type", r.referenceType(), "reference_id", r.referenceId());
            db.insertInto("ticket_messages", "ticket_id", t, "sender_id", me.userId(), "body", body, "attachment_url", attachments.isEmpty() ? null : attachments.get(0),
                    "attachments_json", attachments.isEmpty() ? null : Json.write(attachments));
            return t;
        });
        audit.log("TICKET_CREATED", "support_tickets", id);
        return get(me, id);
    }

    public List<Map<String, Object>> list(AuthUser me, String status, String priority) {
        StringBuilder sb = new StringBuilder("select t.*, u.full_name as raised_by, w.name as workspace_name from support_tickets t join users u on u.id=t.user_id left join workspaces w on w.id=t.workspace_id where 1=1");
        List<Object> kv = new ArrayList<>();
        if (!me.isAdmin()) {
            sb.append(" and t.user_id=:u");
            kv.add("u");
            kv.add(me.userId());
        }
        if (status != null && !status.isBlank()) {
            sb.append(" and t.status=:s");
            kv.add("s");
            kv.add(status);
        }
        if (priority != null && !priority.isBlank()) {
            sb.append(" and t.priority=:p");
            kv.add("p");
            kv.add(priority);
        }
        return db.list(sb + " order by field(t.status,'OPEN','WAITING_CUSTOMER','RESOLVED','CLOSED'), field(t.priority,'URGENT','HIGH','NORMAL','LOW'), t.id desc limit 200", kv.toArray());
    }

    private Row load(AuthUser me, long id) {
        Row t = db.row("select * from support_tickets where id=:id", "id", id).orElseThrow(() -> ApiException.notFound("Ticket"));
        if (!me.isAdmin() && t.l("user_id") != me.userId()) throw ApiException.notFound("Ticket");
        return t;
    }

    public Map<String, Object> get(AuthUser me, long id) {
        Row t = load(me, id);
        Map<String, Object> m = t.api();
        m.put("messages", db.list("select m.*, u.full_name as sender_name, u.admin_role as sender_admin_role from ticket_messages m join users u on u.id=m.sender_id where m.ticket_id=:id" +
                (me.isAdmin() ? "" : " and m.is_internal=0") + " order by m.id", "id", id));
        if ("PAYOUT".equals(t.s("reference_type")) && t.lo("reference_id") != null) { // payout tickets show the UTR and gateway status automatically
            m.put("reference", db.one("select id, payout_ref, amount, net_amount, status, utr, failure_reason, requested_at, processed_at from payouts where id=:id", "id", t.l("reference_id")).orElse(null));
        }
        return m;
    }

    public Map<String, Object> reply(AuthUser me, long id, MessageRequest r) {
        Row t = load(me, id);
        String body = Norm.plain(r.body());
        if (body == null || body.length() < 1 || body.length() > 5000) throw ApiException.invalid("body", "ticket.body", "Message cannot be empty.");
        boolean internal = me.isAdmin() && Boolean.TRUE.equals(r.internal());
        if ("CLOSED".equals(t.s("status"))) throw ApiException.state("This ticket is closed. Open a new one.");
        List<String> att = files(r.attachments());
        db.insertInto("ticket_messages", "ticket_id", id, "sender_id", me.userId(), "body", body, "is_internal", internal, "attachment_url", att.isEmpty() ? null : att.get(0),
                "attachments_json", att.isEmpty() ? null : Json.write(att));
        if (!internal) db.update("update support_tickets set status=:s where id=:id", "s", me.isAdmin() ? "WAITING_CUSTOMER" : "OPEN", "id", id);
        else db.update("update support_tickets set updated_at=:now where id=:id", "now", Time.now(), "id", id);
        return get(me, id);
    }

    public Map<String, Object> patch(AuthUser me, long id, Patch p) {
        Row t = load(me, id);
        Map<String, Object> cols = new LinkedHashMap<>();
        if (p.status() != null) {
            if (!List.of("OPEN", "WAITING_CUSTOMER", "RESOLVED", "CLOSED").contains(p.status())) throw ApiException.badRequest("Unknown status.");
            if (!me.isAdmin() && !List.of("CLOSED").contains(p.status())) throw ApiException.forbidden();
            cols.put("status", p.status());
        }
        if (me.isAdmin()) {
            if (p.priority() != null) {
                if (!List.of("LOW", "NORMAL", "HIGH", "URGENT").contains(p.priority())) throw ApiException.badRequest("Unknown priority.");
                cols.put("priority", p.priority());
            }
            if (p.assignedTo() != null) {
                if (!db.exists("select 1 from users where id=:id and admin_role<>'NONE'", "id", p.assignedTo())) throw ApiException.invalid("assignedTo", "ticket.assignee", "Choose a support staff member.");
                cols.put("assigned_to", p.assignedTo());
            }
        }
        db.updateTable("support_tickets", "id", id, cols);
        audit.log("TICKET_UPDATED", "support_tickets", id, Map.of("status", t.s("status")), cols);
        return get(me, id);
    }

    /** Auto-close 7 days after RESOLVED with no reply. */
    public int autoClose() {
        return db.update("update support_tickets set status='CLOSED' where status='RESOLVED' and updated_at < :t", "t", Time.now().minusDays(7));
    }
}
