package com.marketingpanel.crm;

import com.marketingpanel.auth.AuthUser;
import com.marketingpanel.common.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/** P11 Leads board: NEW -> CONTACTED -> INTERESTED -> WON/LOST with follow-up reminders. */
@Service
public class LeadService {
    private static final List<String> STATUSES = List.of("NEW", "CONTACTED", "INTERESTED", "WON", "LOST");
    private final Db db;
    private final ContactService contacts;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public LeadService(Db db, ContactService contacts, AuditService audit, ApplicationEventPublisher events) {
        this.db = db;
        this.contacts = contacts;
        this.audit = audit;
        this.events = events;
    }

    private static final String SELECT = "select l.*, c.full_name as contact_name, c.phone as contact_phone, c.email as contact_email, " +
            "u.full_name as assignee_name, (l.follow_up_at is not null and l.follow_up_at < :now and l.status not in ('WON','LOST')) as overdue " +
            "from leads l join contacts c on c.id=l.contact_id left join users u on u.id=l.assigned_to ";

    public Map<String, Object> list(AuthUser me, String status, Long assignedTo, String q) {
        StringBuilder sb = new StringBuilder(SELECT + "where l.workspace_id=:ws and l.deleted_at is null");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("ws", me.workspaceId());
        p.put("now", Time.now());
        if (me.isStaffRole()) { // staff see and edit only leads assigned to them
            sb.append(" and l.assigned_to=:me");
            p.put("me", me.userId());
        } else if (assignedTo != null) {
            sb.append(" and l.assigned_to=:at");
            p.put("at", assignedTo);
        }
        if (status != null && !status.isBlank()) {
            sb.append(" and l.status=:st");
            p.put("st", status);
        }
        if (q != null && !q.isBlank()) {
            if (q.trim().length() < 2) throw ApiException.invalid("q", "search.min", "Type at least 2 characters.");
            sb.append(" and (c.full_name like :q or c.phone like :q)");
            p.put("q", "%" + q.trim() + "%");
        }
        sb.append(" order by l.id desc limit 500");
        List<Map<String, Object>> items = Row.api(db.rowsMap(sb.toString(), p));
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String s : STATUSES) counts.put(s, 0L);
        for (Map<String, Object> i : items) counts.merge((String) i.get("status"), 1L, Long::sum);
        return Map.of("items", items, "counts", counts);
    }

    private Row load(AuthUser me, long id) {
        Row r = db.row("select * from leads where id=:id and workspace_id=:ws and deleted_at is null", "id", id, "ws", me.workspaceId())
                .orElseThrow(() -> ApiException.notFound("Lead"));
        if (me.isStaffRole() && (r.lo("assigned_to") == null || r.l("assigned_to") != me.userId())) throw ApiException.notFound("Lead");
        return r;
    }

    public Map<String, Object> get(AuthUser me, long id) {
        load(me, id);
        Map<String, Object> l = Row.api(db.rowsMap(SELECT + "where l.id=:id", Db.map("id", id, "now", Time.now()))).get(0);
        l.put("activities", db.list("select a.*, u.full_name as author from lead_activities a left join users u on u.id=a.created_by " +
                "where a.lead_id=:id order by a.id desc", "id", id));
        return l;
    }

    private void checkAssignee(long ws, Long userId) {
        if (userId == null) return;
        if (!db.exists("select 1 from workspace_members where workspace_id=:ws and user_id=:u and invite_status='ACCEPTED'", "ws", ws, "u", userId))
            throw ApiException.invalid("assignedTo", "lead.assignee", "Choose a team member.");
    }

    private LocalDateTime followUp(String iso) {
        if (iso == null || iso.isBlank()) return null;
        LocalDateTime t = Time.parseUtc(iso);
        if (t.isBefore(Time.now().plusMinutes(5))) throw ApiException.invalid("followUpAt", "lead.followup", "Choose a future time.");
        return t;
    }

    public Map<String, Object> create(AuthUser me, CrmDtos.LeadRequest r) {
        long ws = me.workspaceId();
        long contactId;
        if (r.contactId() != null) {
            if (!db.exists("select 1 from contacts where id=:id and workspace_id=:ws and deleted_at is null", "id", r.contactId(), "ws", ws))
                throw ApiException.invalid("contactId", "lead.contact", "Choose or add a contact.");
            contactId = r.contactId();
        } else if (r.newContact() != null) {
            try {
                contactId = ((Number) contacts.create(ws, me.userId(), r.newContact()).get("id")).longValue();
            } catch (ApiException e) {
                if (e.code() == ErrorCode.DUPLICATE && e.extra().get("existingId") != null) contactId = ((Number) e.extra().get("existingId")).longValue();
                else throw e;
            }
        } else {
            throw ApiException.invalid("contactId", "lead.contact", "Choose or add a contact.");
        }
        checkAssignee(ws, r.assignedTo());
        Long assignee = me.isStaffRole() ? Long.valueOf(me.userId()) : r.assignedTo();
        long id = db.insertInto("leads", "workspace_id", ws, "contact_id", contactId, "source", r.source(), "status", "NEW",
                "assigned_to", assignee, "expected_value", r.expectedValue(), "follow_up_at", followUp(r.followUpAt()),
                "requirement", r.requirement(), "created_by", me.userId());
        addActivity(id, "STATUS_CHANGE", "Lead created", me.userId());
        audit.log("LEAD_CREATED", "leads", id);
        return get(me, id);
    }

    public Map<String, Object> patch(AuthUser me, long id, CrmDtos.LeadPatch r) {
        Row cur = load(me, id);
        long ws = me.workspaceId();
        Map<String, Object> cols = new LinkedHashMap<>();
        String newStatus = r.status() == null || r.status().isBlank() ? null : r.status();
        if (newStatus != null && !STATUSES.contains(newStatus)) throw ApiException.invalid("status", "lead.status", "Choose a status.");
        String effective = newStatus != null ? newStatus : cur.s("status");
        if ("LOST".equals(effective)) {
            String reason = r.lostReason() != null && !r.lostReason().isBlank() ? Norm.plain(r.lostReason()) : cur.s("lost_reason");
            if (reason == null || reason.isBlank()) throw ApiException.invalid("lostReason", "lead.lost", "Tell us why this lead was lost.");
            cols.put("lost_reason", reason);
        } else if (newStatus != null) {
            cols.put("lost_reason", null);
        }
        if (newStatus != null && !newStatus.equals(cur.s("status"))) cols.put("status", newStatus);
        if (r.assignedTo() != null && !me.isStaffRole()) {
            checkAssignee(ws, r.assignedTo());
            cols.put("assigned_to", r.assignedTo());
        }
        if (Boolean.TRUE.equals(r.clearAssignee()) && !me.isStaffRole()) cols.put("assigned_to", null);
        if (Boolean.TRUE.equals(r.clearFollowUp())) cols.put("follow_up_at", null);
        else if (r.followUpAt() != null) cols.put("follow_up_at", followUp(r.followUpAt()));
        if (r.expectedValue() != null) cols.put("expected_value", r.expectedValue());
        if (r.requirement() != null) cols.put("requirement", Norm.plain(r.requirement()));
        db.updateTable("leads", "id", id, cols, "workspace_id", ws);
        if (cols.containsKey("status")) {
            addActivity(id, "STATUS_CHANGE", "Status changed from " + cur.s("status") + " to " + newStatus
                    + ("LOST".equals(newStatus) ? " (" + cols.get("lost_reason") + ")" : ""), me.userId());
            events.publishEvent(new Events.LeadStatusChanged(ws, cur.l("contact_id"), id, newStatus));
        }
        if (cols.containsKey("follow_up_at") && cols.get("follow_up_at") != null)
            addActivity(id, "FOLLOW_UP", "Follow-up set for " + Time.fmt((LocalDateTime) cols.get("follow_up_at")), me.userId());
        audit.log("LEAD_UPDATED", "leads", id);
        return get(me, id);
    }

    public Map<String, Object> addNote(AuthUser me, long id, CrmDtos.NoteRequest r) {
        load(me, id);
        String type = r.type() == null || r.type().isBlank() ? "NOTE" : r.type();
        addActivity(id, type, r.note(), me.userId());
        return get(me, id);
    }

    public void addActivity(long leadId, String type, String text, Long userId) {
        db.insertInto("lead_activities", "lead_id", leadId, "type", type, "description", text.length() > 1000 ? text.substring(0, 1000) : text, "created_by", userId);
    }

    public void delete(AuthUser me, long id) {
        load(me, id);
        db.update("update leads set deleted_at=:now where id=:id", "now", Time.now(), "id", id);
        audit.log("LEAD_DELETED", "leads", id);
    }

    /** Reminder 15 minutes before follow_up_at; overdue ones are flagged red in the UI. Run by the scheduler. */
    public int sendReminders() {
        LocalDateTime now = Time.now();
        int n = 0;
        for (Row l : db.rows("select l.id, l.workspace_id, l.assigned_to, c.full_name from leads l join contacts c on c.id=l.contact_id " +
                "where l.deleted_at is null and l.status not in ('WON','LOST') and l.follow_up_at is not null and l.follow_up_at <= :t " +
                "and l.follow_up_at > :old and not exists (select 1 from notifications nf where nf.workspace_id=l.workspace_id " +
                "and nf.type='LEAD_FOLLOWUP' and nf.link=concat('/leads/', l.id) and nf.created_at > l.updated_at) limit 500",
                "t", now.plusMinutes(15), "old", now.minusDays(2))) {
            db.insertInto("notifications", "workspace_id", l.l("workspace_id"), "user_id", l.lo("assigned_to"), "type", "LEAD_FOLLOWUP",
                    "title", "Follow up with " + l.s("full_name"), "body", "A lead follow-up is due soon.", "link", "/leads/" + l.l("id"));
            n++;
        }
        return n;
    }
}
