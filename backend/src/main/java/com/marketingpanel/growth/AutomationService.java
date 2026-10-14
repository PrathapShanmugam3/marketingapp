package com.marketingpanel.growth;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.common.*;
import org.springframework.stereotype.Service;

import java.util.*;

/** P29 Automation builder: birthday wishes, welcome series, keyword auto-replies and lead follow-ups. */
@Service
public class AutomationService {
    static final Set<String> TRIGGERS = Set.of("CONTACT_CREATED", "TAG_ADDED", "BIRTHDAY", "FORM_SUBMITTED", "KEYWORD", "LEAD_STATUS", "LINK_CLICKED");
    static final Set<String> BLOCKED_KEYWORDS = Set.of("stop", "unsubscribe", "நிறுத்து");
    private final Db db;
    private final PlanService plans;
    private final AuditService audit;

    public AutomationService(Db db, PlanService plans, AuditService audit) {
        this.db = db;
        this.plans = plans;
        this.audit = audit;
    }

    public record AutomationRequest(String name, String triggerType, JsonNode triggerConfig, JsonNode steps, Integer allowReentryDays) {}

    public List<Map<String, Object>> list(long ws) {
        return db.list("select * from automations where workspace_id=:ws order by id desc", "ws", ws);
    }

    public Map<String, Object> get(long ws, long id) {
        Map<String, Object> m = db.one("select * from automations where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Automation"));
        m.put("runStats", db.list("select status, count(*) as n from automation_runs where automation_id=:id group by status", "id", id));
        return m;
    }

    private void validate(long ws, AutomationRequest r, Long exceptId, String status) {
        List<FieldErr> errs = new ArrayList<>();
        String name = Norm.plain(Norm.name(r.name()));
        if (name == null || name.length() < 3 || name.length() > 100) errs.add(new FieldErr("name", "auto.name", "Enter a name (3 to 100 characters)."));
        else if (db.exists("select 1 from automations where workspace_id=:ws and lower(name)=lower(:n)" + (exceptId != null ? " and id<>:id" : ""), "ws", ws, "n", name, "id", exceptId == null ? 0L : exceptId))
            errs.add(new FieldErr("name", "auto.name.exists", "You already have an automation with this name."));
        if (r.triggerType() == null || !TRIGGERS.contains(r.triggerType())) {
            errs.add(new FieldErr("triggerType", "auto.trigger", "Choose what starts this automation."));
        } else {
            JsonNode cfg = r.triggerConfig() == null ? Json.tree(Map.of()) : r.triggerConfig();
            switch (r.triggerType()) {
                case "TAG_ADDED" -> {
                    if (!db.exists("select 1 from tags where id=:t and workspace_id=:ws", "t", cfg.path("tagId").asLong(0), "ws", ws)) errs.add(new FieldErr("triggerConfig", "auto.tag", "Choose a tag."));
                }
                case "FORM_SUBMITTED" -> {
                    if (!db.exists("select 1 from lead_forms where id=:t and workspace_id=:ws", "t", cfg.path("formId").asLong(0), "ws", ws)) errs.add(new FieldErr("triggerConfig", "auto.form", "Choose a form."));
                }
                case "LINK_CLICKED" -> {
                    if (!db.exists("select 1 from short_links where id=:t and workspace_id=:ws", "t", cfg.path("linkId").asLong(0), "ws", ws)) errs.add(new FieldErr("triggerConfig", "auto.link", "Choose a link."));
                }
                case "LEAD_STATUS" -> {
                    if (!List.of("NEW", "CONTACTED", "INTERESTED", "WON", "LOST").contains(cfg.path("status").asText())) errs.add(new FieldErr("triggerConfig", "auto.status", "Choose a lead status."));
                }
                case "KEYWORD" -> {
                    String kw = cfg.path("keyword").asText("").trim().toLowerCase();
                    if (kw.isEmpty() || kw.length() > 30) errs.add(new FieldErr("triggerConfig", "auto.keyword", "Keyword must be 1 to 30 characters."));
                    else if (BLOCKED_KEYWORDS.contains(kw)) errs.add(new FieldErr("triggerConfig", "auto.keyword.reserved", "This keyword is reserved for opt-outs."));
                    else if (db.exists("select 1 from automations where workspace_id=:ws and trigger_type='KEYWORD' and status='ACTIVE' and lower(json_unquote(json_extract(trigger_config,'$.keyword')))=:k" +
                            (exceptId != null ? " and id<>:id" : ""), "ws", ws, "k", kw, "id", exceptId == null ? 0L : exceptId))
                        errs.add(new FieldErr("triggerConfig", "auto.keyword.used", "This keyword is already used by another automation."));
                }
                default -> {}
            }
        }
        int reentry = r.allowReentryDays() == null ? -1 : r.allowReentryDays();
        if (reentry < 0 || reentry > 365) errs.add(new FieldErr("allowReentryDays", "auto.reentry", "Enter 0 to 365 days."));
        validateSteps(ws, r.steps(), errs);
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
    }

    /** 1-20 steps; IF branches only jump forward, so loops are impossible and every flow ends. */
    void validateSteps(long ws, JsonNode steps, List<FieldErr> errs) {
        if (steps == null || !steps.isArray() || steps.isEmpty()) {
            errs.add(new FieldErr("steps", "auto.steps.min", "Add at least one step."));
            return;
        }
        if (steps.size() > 20) {
            errs.add(new FieldErr("steps", "auto.steps.max", "An automation can have up to 20 steps."));
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            JsonNode s = steps.get(i);
            String t = s.path("type").asText("");
            switch (t) {
                case "SEND_TEMPLATE" -> {
                    Row tpl = db.row("select * from wa_templates where id=:id and workspace_id=:ws and status='APPROVED'", "id", s.path("templateId").asLong(0), "ws", ws).orElse(null);
                    if (tpl == null) errs.add(new FieldErr("steps", "auto.step.template", "Step " + (i + 1) + ": choose an approved template."));
                    else {
                        List<FieldErr> inner = new ArrayList<>();
                        com.marketingpanel.campaign.VariableResolver.validate(s.get("variables"), tpl.i("variable_count"), inner);
                        if (!inner.isEmpty()) errs.add(new FieldErr("steps", "auto.step.vars", "Step " + (i + 1) + ": map every variable in this template."));
                    }
                }
                case "WAIT" -> {
                    int m = s.path("minutes").asInt(0);
                    if (m < 5 || m > 43200) errs.add(new FieldErr("steps", "auto.step.wait", "Step " + (i + 1) + ": wait between 5 minutes and 30 days."));
                }
                case "ADD_TAG", "REMOVE_TAG" -> {
                    if (!db.exists("select 1 from tags where id=:t and workspace_id=:ws", "t", s.path("tagId").asLong(0), "ws", ws)) errs.add(new FieldErr("steps", "auto.step.tag", "Step " + (i + 1) + ": choose a tag."));
                }
                case "IF" -> {
                    if (!List.of("REPLIED", "CLICKED", "READ").contains(s.path("condition").asText())) errs.add(new FieldErr("steps", "auto.step.if", "Step " + (i + 1) + ": choose a condition."));
                    int eg = s.path("elseGoto").asInt(-1);
                    if (i == steps.size() - 1) errs.add(new FieldErr("steps", "auto.branch", "Step " + (i + 1) + ": every branch must have at least one step."));
                    if (eg != -1 && (eg <= i || eg >= steps.size())) errs.add(new FieldErr("steps", "auto.loop", "Step " + (i + 1) + ": a flow must end; loops are not allowed."));
                }
                case "ASSIGN_LEAD" -> {
                    if (!db.exists("select 1 from workspace_members where workspace_id=:ws and user_id=:u and invite_status='ACCEPTED'", "ws", ws, "u", s.path("userId").asLong(0)))
                        errs.add(new FieldErr("steps", "auto.step.assign", "Step " + (i + 1) + ": choose a team member."));
                }
                case "NOTIFY" -> {
                    String msg = s.path("message").asText("");
                    if (msg.isBlank() || msg.length() > 300) errs.add(new FieldErr("steps", "auto.step.notify", "Step " + (i + 1) + ": write a short message (up to 300 characters)."));
                }
                default -> errs.add(new FieldErr("steps", "auto.step.type", "Step " + (i + 1) + ": unknown step type."));
            }
        }
    }

    public Map<String, Object> create(long ws, long userId, AutomationRequest r) {
        plans.requireFeature(ws, "automations");
        validate(ws, r, null, "DRAFT");
        long id = db.insertInto("automations", "workspace_id", ws, "name", Norm.plain(Norm.name(r.name())), "trigger_type", r.triggerType(),
                "trigger_config", (r.triggerConfig() == null ? Json.tree(Map.of()) : r.triggerConfig()).toString(), "steps_json", r.steps().toString(),
                "allow_reentry_days", r.allowReentryDays(), "created_by", userId);
        audit.log("AUTOMATION_CREATED", "automations", id);
        return get(ws, id);
    }

    public Map<String, Object> update(long ws, long id, AutomationRequest r) {
        Map<String, Object> cur = get(ws, id);
        validate(ws, r, id, String.valueOf(cur.get("status")));
        db.update("update automations set name=:n, trigger_type=:t, trigger_config=:c, steps_json=:s, allow_reentry_days=:a where id=:id",
                "n", Norm.plain(Norm.name(r.name())), "t", r.triggerType(), "c", (r.triggerConfig() == null ? Json.tree(Map.of()) : r.triggerConfig()).toString(),
                "s", r.steps().toString(), "a", r.allowReentryDays(), "id", id);
        return get(ws, id);
    }

    public Map<String, Object> setStatus(long ws, long id, String status) {
        if (!List.of("ACTIVE", "PAUSED", "DRAFT").contains(status)) throw ApiException.badRequest("Unknown status.");
        Row a = db.row("select * from automations where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Automation"));
        if ("ACTIVE".equals(status)) {
            plans.requireFeature(ws, "automations");
            validate(ws, new AutomationRequest(a.s("name"), a.s("trigger_type"), Json.tree(a.s("trigger_config")), Json.tree(a.s("steps_json")), a.i("allow_reentry_days")), id, "ACTIVE");
        }
        db.update("update automations set status=:s where id=:id", "s", status, "id", id);
        if ("PAUSED".equals(status))
            db.update("update automation_runs set status='EXITED', exit_reason='automation paused', finished_at=:now where automation_id=:id and status in ('RUNNING','WAITING')", "now", Time.now(), "id", id);
        audit.log("AUTOMATION_" + status, "automations", id);
        return get(ws, id);
    }

    public void delete(long ws, long id) {
        get(ws, id);
        db.update("delete from automations where id=:id and workspace_id=:ws", "id", id, "ws", ws);
    }

    public Map<String, Object> runs(long ws, long id) {
        get(ws, id);
        return Map.of("items", db.list("select r.*, c.full_name from automation_runs r join contacts c on c.id=r.contact_id where r.automation_id=:id order by r.id desc limit 100", "id", id));
    }
}
