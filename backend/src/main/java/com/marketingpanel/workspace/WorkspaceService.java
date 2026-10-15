package com.marketingpanel.workspace;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

@Service
public class WorkspaceService {
    private final Db db;
    private final PlanService plans;
    private final AuditService audit;
    private final Notifier notifier;
    private final Settings settings;
    private final RateLimiter limiter;

    public WorkspaceService(Db db, PlanService plans, AuditService audit, Notifier notifier, Settings settings, RateLimiter limiter) {
        this.db = db;
        this.plans = plans;
        this.audit = audit;
        this.notifier = notifier;
        this.settings = settings;
        this.limiter = limiter;
    }

    public Map<String, Object> get(long ws) {
        Map<String, Object> w = db.one("select w.*, p.code as plan_code, p.name as plan_name from workspaces w join plans p on p.id=w.plan_id " +
                "where w.id=:id and w.deleted_at is null", "id", ws).orElseThrow(() -> ApiException.notFound("Workspace"));
        Map<String, Object> checklist = new LinkedHashMap<>();
        checklist.put("whatsappConnected", db.exists("select 1 from wa_accounts where workspace_id=:ws and status='CONNECTED'", "ws", ws));
        checklist.put("contactsImported", db.exists("select 1 from contacts where workspace_id=:ws and deleted_at is null limit 1", "ws", ws));
        checklist.put("firstCampaign", db.exists("select 1 from campaigns where workspace_id=:ws and status not in ('DRAFT') limit 1", "ws", ws));
        w.put("onboarding", checklist);
        w.put("states", new ArrayList<>(States.CODE_BY_STATE.keySet()));
        return w;
    }

    public Map<String, Object> update(long ws, WorkspaceDtos.UpdateWorkspace r) {
        List<FieldErr> errs = new ArrayList<>();
        if (!States.valid(r.state())) errs.add(new FieldErr("state", "state.invalid", "Choose your state."));
        else if (r.gstin() != null && !States.gstinMatches(r.gstin(), r.state()))
            errs.add(new FieldErr("gstin", "gstin.state", "GSTIN does not match the selected state."));
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        Map<String, Object> old = db.one("select name,phone,email,state,gstin from workspaces where id=:id", "id", ws).orElseThrow();
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("name", r.name());
        cols.put("phone", r.phone());
        cols.put("email", r.email());
        cols.put("address_line", r.addressLine());
        cols.put("city", r.city());
        cols.put("state", r.state());
        cols.put("pincode", r.pincode());
        cols.put("gstin", r.gstin());
        cols.put("language", r.language());
        cols.put("logo_url", r.logoUrl());
        cols.put("brand_color", r.brandColor());
        db.updateTable("workspaces", "id", ws, cols);
        audit.log("WORKSPACE_UPDATED", "workspaces", ws, old, Map.of("name", r.name(), "state", r.state(), "gstin", String.valueOf(r.gstin())));
        return get(ws);
    }

    // ------------------------------------------------------------------ team

    public Map<String, Object> team(long ws) {
        List<Map<String, Object>> members = db.list("select m.id, m.user_id, m.role, m.invite_status, m.invite_email, m.invite_expires_at, m.joined_at, " +
                "u.full_name, u.email, u.phone, u.last_login_at from workspace_members m left join users u on u.id=m.user_id " +
                "where m.workspace_id=:ws and m.invite_status in ('ACCEPTED','PENDING') order by m.id", "ws", ws);
        Row plan = plans.plan(ws);
        return Map.of("members", members, "maxUsers", plan.l("max_users"));
    }

    public Map<String, Object> invite(long ws, WorkspaceDtos.InviteRequest r) {
        Row plan = plans.plan(ws);
        long used = db.count("select count(*) from workspace_members where workspace_id=:ws and invite_status in ('ACCEPTED','PENDING')", "ws", ws);
        if (used >= plan.l("max_users")) throw ApiException.planLimit("Your plan allows " + plan.l("max_users") + " users. Upgrade to add more.");
        boolean already = db.exists("select 1 from workspace_members m left join users u on u.id=m.user_id where m.workspace_id=:ws " +
                "and m.invite_status in ('ACCEPTED','PENDING') and (m.invite_email=:e or u.email=:e)", "ws", ws, "e", r.email());
        if (already) throw ApiException.duplicate("email", "team.exists", "This person is already in your team.");
        String token = Ids.randomToken(32);
        long id = db.insertInto("workspace_members", "workspace_id", ws, "invite_email", r.email(), "role", r.role(),
                "invite_status", "PENDING", "invite_token_hash", Hashing.sha256(token), "invite_expires_at", Time.now().plusDays(7),
                "invited_by", Auth.userId());
        sendInvite(ws, r.email(), token);
        audit.log("TEAM_INVITED", "workspace_members", id, null, Map.of("email", r.email(), "role", r.role()));
        return Map.of("id", id);
    }

    private void sendInvite(long ws, String email, String token) {
        String wsName = db.scalar("select name from workspaces where id=:id", String.class, "id", ws);
        notifier.email(email, "You're invited to " + wsName + " on Marketing Panel",
                "Accept your invite (valid 7 days): " + settings.frontendUrl + "/invite?token=" + token);
    }

    public void resend(long ws, long memberId) {
        Row m = db.row("select * from workspace_members where id=:id and workspace_id=:ws and invite_status='PENDING'", "id", memberId, "ws", ws)
                .orElseThrow(() -> ApiException.notFound("Invite"));
        limiter.check("invite-resend:" + memberId, 1, Duration.ofHours(1));
        String token = Ids.randomToken(32);
        db.update("update workspace_members set invite_token_hash=:h, invite_expires_at=:e where id=:id",
                "h", Hashing.sha256(token), "e", Time.now().plusDays(7), "id", memberId);
        sendInvite(ws, m.s("invite_email"), token);
    }

    public void changeRole(long ws, long memberId, String role) {
        Row m = db.row("select * from workspace_members where id=:id and workspace_id=:ws and invite_status in ('ACCEPTED','PENDING')",
                "id", memberId, "ws", ws).orElseThrow(() -> ApiException.notFound("Member"));
        if ("OWNER".equals(m.s("role")) && !"OWNER".equals(role) && lastOwner(ws))
            throw ApiException.state("The last Owner cannot be demoted.");
        if ("AGENCY_ADMIN".equals(m.s("role"))) throw ApiException.state("Agency admins cannot be changed here.");
        db.update("update workspace_members set role=:r where id=:id", "r", role, "id", memberId);
        audit.log("TEAM_ROLE_CHANGED", "workspace_members", memberId, Map.of("role", m.s("role")), Map.of("role", role));
    }

    public void remove(long ws, long memberId) {
        Row m = db.row("select * from workspace_members where id=:id and workspace_id=:ws and invite_status in ('ACCEPTED','PENDING')",
                "id", memberId, "ws", ws).orElseThrow(() -> ApiException.notFound("Member"));
        if (("OWNER".equals(m.s("role")) || "AGENCY_ADMIN".equals(m.s("role"))) && "ACCEPTED".equals(m.s("invite_status")) && lastOwner(ws))
            throw ApiException.state("The last Owner cannot be removed.");
        if ("PENDING".equals(m.s("invite_status"))) {
            db.update("update workspace_members set invite_status='REVOKED', invite_token_hash=null where id=:id", "id", memberId);
        } else {
            db.update("delete from workspace_members where id=:id", "id", memberId);
            if (m.lo("user_id") != null) {
                db.update("update leads set assigned_to=null where workspace_id=:ws and assigned_to=:u", "ws", ws, "u", m.l("user_id"));
                db.update("update conversations set assigned_to=null where workspace_id=:ws and assigned_to=:u", "ws", ws, "u", m.l("user_id"));
            }
        }
        audit.log("TEAM_REMOVED", "workspace_members", memberId, Map.of("role", m.s("role")), null);
    }

    private boolean lastOwner(long ws) {
        return db.count("select count(*) from workspace_members where workspace_id=:ws and role in ('OWNER','AGENCY_ADMIN') and invite_status='ACCEPTED'", "ws", ws) <= 1;
    }

    // -------------------------------------------------------- agency clients

    public List<Map<String, Object>> clients(long parentWs) {
        return db.list("select w.id, w.name, w.city, w.state, w.status, w.wallet_balance, w.logo_url, w.brand_color, w.created_at, " +
                "(select count(*) from contacts c where c.workspace_id=w.id and c.deleted_at is null) as contacts " +
                "from workspaces w where w.parent_workspace_id=:p and w.deleted_at is null order by w.id desc", "p", parentWs);
    }

    public Map<String, Object> createClient(long parentWs, long userId, String name, String city, String state, String logoUrl, String brandColor) {
        Row plan = plans.plan(parentWs);
        long n = db.count("select count(*) from workspaces where parent_workspace_id=:p and deleted_at is null", "p", parentWs);
        if (n >= plan.l("max_workspaces")) throw ApiException.planLimit("Your plan allows " + plan.l("max_workspaces") + " client workspaces. Extra seats are billed.");
        if (!States.valid(state)) throw ApiException.invalid("state", "state.invalid", "Choose your state.");
        Row parent = db.row("select * from workspaces where id=:id", "id", parentWs).orElseThrow();
        long id = db.tx(() -> {
            long wid = db.insertInto("workspaces", "parent_workspace_id", parentWs, "name", name, "business_type", "OTHER",
                    "phone", parent.s("phone"), "city", city, "state", state, "plan_id", parent.l("plan_id"), "status", "ACTIVE",
                    "logo_url", logoUrl, "brand_color", brandColor);
            db.insertInto("workspace_members", "workspace_id", wid, "user_id", userId, "role", "AGENCY_ADMIN",
                    "invite_status", "ACCEPTED", "joined_at", Time.now());
            return wid;
        });
        audit.log("CLIENT_WORKSPACE_CREATED", "workspaces", id, null, Map.of("name", name));
        return Map.of("id", id);
    }
}
