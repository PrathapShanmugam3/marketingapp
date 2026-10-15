package com.marketingpanel.growth;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.common.*;
import com.marketingpanel.inbox.DirectSendService;
import com.marketingpanel.whatsapp.WaException;
import com.marketingpanel.campaign.VariableResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Starts automation runs from domain events and executes them step by step (SRS P29).
 * Rules: quiet hours (9 PM - 9 AM IST) delay marketing sends, the per-contact frequency cap applies,
 * a contact exits on opt-out, and an empty wallet pauses the flow instead of failing it.
 */
@Component
public class AutomationEngine {
    private static final Logger log = LoggerFactory.getLogger(AutomationEngine.class);
    private final Db db;
    private final DirectSendService sender;
    private final NotificationService notifications;

    public AutomationEngine(Db db, DirectSendService sender, NotificationService notifications) {
        this.db = db;
        this.sender = sender;
        this.notifications = notifications;
    }

    // --------------------------------------------------------------- triggers

    @EventListener
    public void onContactCreated(Events.ContactCreated e) {
        startAll(e.workspaceId(), "CONTACT_CREATED", e.contactId(), c -> true);
    }

    @EventListener
    public void onTagAdded(Events.TagAdded e) {
        startAll(e.workspaceId(), "TAG_ADDED", e.contactId(), c -> c.path("tagId").asLong() == e.tagId());
    }

    @EventListener
    public void onForm(Events.FormSubmitted e) {
        startAll(e.workspaceId(), "FORM_SUBMITTED", e.contactId(), c -> c.path("formId").asLong() == e.formId());
    }

    @EventListener
    public void onLead(Events.LeadStatusChanged e) {
        startAll(e.workspaceId(), "LEAD_STATUS", e.contactId(), c -> e.status().equals(c.path("status").asText()));
    }

    @EventListener
    public void onLink(Events.LinkClicked e) {
        startAll(e.workspaceId(), "LINK_CLICKED", e.contactId(), c -> c.path("linkId").asLong() == e.linkId());
    }

    @EventListener
    public void onInbound(Events.InboundMessage e) {
        String text = e.text() == null ? "" : e.text().trim().toLowerCase();
        if (text.isEmpty()) return;
        startAll(e.workspaceId(), "KEYWORD", e.contactId(), c -> {
            String kw = c.path("keyword").asText("").trim().toLowerCase();
            return !kw.isEmpty() && (text.equals(kw) || text.startsWith(kw + " "));
        });
    }

    private void startAll(long ws, String trigger, long contactId, java.util.function.Predicate<JsonNode> match) {
        for (Row a : db.rows("select * from automations where workspace_id=:ws and trigger_type=:t and status='ACTIVE'", "ws", ws, "t", trigger)) {
            try {
                if (match.test(Json.tree(a.s("trigger_config")))) start(a, contactId);
            } catch (Exception ex) {
                log.warn("Automation {} could not start for contact {}: {}", a.l("id"), contactId, ex.toString());
            }
        }
    }

    public boolean startManual(long ws, long automationId, long contactId) {
        Row a = db.row("select * from automations where id=:id and workspace_id=:ws and status='ACTIVE'", "id", automationId, "ws", ws)
                .orElseThrow(() -> ApiException.state("The automation must be active."));
        return start(a, contactId);
    }

    /** Re-entry rules: 0 = once per contact, otherwise not again within N days. */
    boolean start(Row a, long contactId) {
        Row c = db.row("select opt_in_status, deleted_at from contacts where id=:id", "id", contactId).orElse(null);
        if (c == null || c.t("deleted_at") != null || "OPTED_OUT".equals(c.s("opt_in_status"))) return false;
        int days = a.i("allow_reentry_days");
        Row last = db.row("select started_at from automation_runs where automation_id=:a and contact_id=:c order by id desc limit 1", "a", a.l("id"), "c", contactId).orElse(null);
        if (last != null && (days == 0 || last.t("started_at").isAfter(Time.now().minusDays(days)))) return false;
        if (db.exists("select 1 from automation_runs where automation_id=:a and contact_id=:c and status in ('RUNNING','WAITING') limit 1", "a", a.l("id"), "c", contactId)) return false;
        db.insertInto("automation_runs", "automation_id", a.l("id"), "contact_id", contactId, "status", "RUNNING", "next_run_at", Time.now());
        db.update("update automations set runs_count=runs_count+1 where id=:id", "id", a.l("id"));
        return true;
    }

    /** Birthday trigger: contacts whose birthday is today (IST) in workspaces with an active BIRTHDAY automation; sends happen 9 AM - 8 PM IST. */
    public int startBirthdays() {
        LocalDateTime ist = Time.toIst(Time.now());
        if (ist.getHour() < 9 || ist.getHour() >= 20) return 0;
        int n = 0;
        LocalDate today = ist.toLocalDate();
        for (Row a : db.rows("select * from automations where trigger_type='BIRTHDAY' and status='ACTIVE'")) {
            for (Row c : db.rows("select id from contacts where workspace_id=:ws and deleted_at is null and opt_in_status<>'OPTED_OUT' and date_of_birth is not null " +
                            "and month(date_of_birth)=:m and day(date_of_birth)=:d and not exists (select 1 from automation_runs r where r.automation_id=:a and r.contact_id=contacts.id " +
                            "and r.started_at >= :yearStart) limit 500", "ws", a.l("workspace_id"), "m", today.getMonthValue(), "d", today.getDayOfMonth(), "a", a.l("id"),
                    "yearStart", Time.startOfIstDayUtc(today.withDayOfYear(1)))) {
                if (start(a, c.l("id"))) n++;
            }
        }
        return n;
    }

    // ----------------------------------------------------------------- runner

    /** Executes due runs. Called every 30 s by the scheduler (ShedLock); safe with several nodes. */
    public int processDue() {
        List<Row> due = db.tx(() -> {
            List<Row> rows = db.rows("select * from automation_runs where status in ('RUNNING','WAITING') and next_run_at <= :now order by next_run_at limit 100 for update skip locked", "now", Time.now());
            for (Row r : rows) db.update("update automation_runs set next_run_at=:t where id=:id", "t", Time.now().plusMinutes(2), "id", r.l("id")); // soft lock
            return rows;
        });
        for (Row r : due) {
            try {
                step(r);
            } catch (Exception e) {
                log.warn("Automation run {} failed: {}", r.l("id"), e.toString());
                db.update("update automation_runs set status='FAILED', exit_reason=:e, finished_at=:now where id=:id", "e", clip(e.toString(), 100), "now", Time.now(), "id", r.l("id"));
            }
        }
        return due.size();
    }

    private static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    private void step(Row run) {
        long id = run.l("id");
        Row a = db.row("select * from automations where id=:id", "id", run.l("automation_id")).orElse(null);
        Row contact = db.row("select * from contacts where id=:id", "id", run.l("contact_id")).orElse(null);
        if (a == null || contact == null || contact.t("deleted_at") != null) {
            finish(id, "EXITED", "contact or automation removed");
            return;
        }
        if ("OPTED_OUT".equals(contact.s("opt_in_status"))) {
            finish(id, "EXITED", "opted out");
            return;
        }
        if (!"ACTIVE".equals(a.s("status"))) {
            finish(id, "EXITED", "automation paused");
            return;
        }
        long ws = a.l("workspace_id");
        JsonNode steps = Json.tree(a.s("steps_json"));
        int cur = run.i("current_step");
        for (int guard = 0; guard < 25; guard++) {
            if (cur >= steps.size()) {
                finish(id, "COMPLETED", null);
                return;
            }
            JsonNode s = steps.get(cur);
            switch (s.path("type").asText()) {
                case "WAIT" -> {
                    db.update("update automation_runs set status='WAITING', current_step=:c, next_run_at=:t where id=:id", "c", cur + 1, "t", Time.now().plusMinutes(s.path("minutes").asInt(5)), "id", id);
                    return;
                }
                case "SEND_TEMPLATE" -> {
                    Row tpl = db.row("select * from wa_templates where id=:id", "id", s.path("templateId").asLong()).orElse(null);
                    if (tpl == null) {
                        finish(id, "FAILED", "template removed");
                        return;
                    }
                    if ("MARKETING".equals(tpl.s("category")) && inQuietHours()) {
                        db.update("update automation_runs set status='WAITING', current_step=:c, next_run_at=:t where id=:id", "c", cur, "t", nextNineAm(), "id", id);
                        return;
                    }
                    try {
                        List<String> params = VariableResolver.resolve(s.get("variables"), tpl.i("variable_count"), contact);
                        DirectSendService.Result res = sender.sendTemplate(ws, contact.l("id"), tpl.l("id"), params, s.path("headerMediaUrl").asText(null), null, null, true);
                        if (res.skipped() == DirectSendService.Skip.OPTED_OUT) {
                            finish(id, "EXITED", "opted out");
                            return;
                        }
                        if (res.skipped() == DirectSendService.Skip.FREQ_CAP) { // try again later, same step
                            db.update("update automation_runs set status='WAITING', current_step=:c, next_run_at=:t where id=:id", "c", cur, "t", Time.now().plusHours(1), "id", id);
                            return;
                        }
                    } catch (IllegalArgumentException e) {
                        finish(id, "FAILED", "missing variable value");
                        return;
                    } catch (ApiException e) {
                        if (e.code() == ErrorCode.INSUFFICIENT_BALANCE) { // flow pauses while the wallet is empty
                            notifications.notifyOnce(ws, "AUTOMATION_WALLET", 24, "Automation paused: wallet is empty", "Top up your wallet to continue \"" + a.s("name") + "\".", "/settings/billing");
                            db.update("update automation_runs set status='WAITING', current_step=:c, next_run_at=:t where id=:id", "c", cur, "t", Time.now().plusHours(1), "id", id);
                        } else if (e.code() == ErrorCode.WHATSAPP_NOT_CONNECTED) {
                            db.update("update automation_runs set status='WAITING', current_step=:c, next_run_at=:t where id=:id", "c", cur, "t", Time.now().plusHours(1), "id", id);
                        } else {
                            finish(id, "FAILED", clip(e.getMessage(), 100));
                        }
                        return;
                    } catch (WaException e) {
                        if (e.retryable()) {
                            db.update("update automation_runs set status='WAITING', current_step=:c, next_run_at=:t where id=:id", "c", cur, "t", Time.now().plusMinutes(5), "id", id);
                        } else {
                            finish(id, "FAILED", clip("WhatsApp: " + e.getMessage(), 100));
                        }
                        return;
                    }
                    cur++;
                }
                case "ADD_TAG" -> {
                    int n = db.update("insert ignore into contact_tags (contact_id, tag_id) values (:c,:t)", "c", contact.l("id"), "t", s.path("tagId").asLong());
                    cur++;
                }
                case "REMOVE_TAG" -> {
                    db.update("delete from contact_tags where contact_id=:c and tag_id=:t", "c", contact.l("id"), "t", s.path("tagId").asLong());
                    cur++;
                }
                case "IF" -> cur = evaluate(run, contact, s) ? cur + 1 : (s.path("elseGoto").asInt(-1) == -1 ? steps.size() : s.path("elseGoto").asInt());
                case "ASSIGN_LEAD" -> {
                    db.update("update leads set assigned_to=:u where workspace_id=:ws and contact_id=:c and deleted_at is null order by id desc limit 1", "u", s.path("userId").asLong(), "ws", ws, "c", contact.l("id"));
                    cur++;
                }
                case "NOTIFY" -> {
                    notifications.notify(ws, null, "AUTOMATION_NOTIFY", clip(s.path("message").asText(), 120), contact.s("full_name") + " (" + contact.s("phone") + ")", "/contacts/" + contact.l("id"));
                    cur++;
                }
                default -> cur++;
            }
            db.update("update automation_runs set current_step=:c where id=:id", "c", cur, "id", id);
        }
        finish(id, "COMPLETED", null);
    }

    private boolean evaluate(Row run, Row contact, JsonNode s) {
        LocalDateTime since = run.t("started_at");
        return switch (s.path("condition").asText()) {
            case "REPLIED" -> db.exists("select 1 from conversations where workspace_id=:ws and contact_id=:c and last_inbound_at > :t", "ws", contact.l("workspace_id"), "c", contact.l("id"), "t", since);
            case "CLICKED" -> db.exists("select 1 from link_clicks where contact_id=:c and clicked_at > :t and is_bot=0 limit 1", "c", contact.l("id"), "t", since);
            case "READ" -> db.exists("select 1 from messages m join conversations cv on cv.id=m.conversation_id where cv.contact_id=:c and m.direction='OUT' and m.status='READ' and m.created_at > :t limit 1", "c", contact.l("id"), "t", since);
            default -> false;
        };
    }

    private void finish(long id, String status, String reason) {
        db.update("update automation_runs set status=:s, exit_reason=:r, finished_at=:now, next_run_at=null where id=:id", "s", status, "r", reason, "now", Time.now(), "id", id);
    }

    static boolean inQuietHours() {
        int h = Time.toIst(Time.now()).getHour();
        return h >= 21 || h < 9;
    }

    static LocalDateTime nextNineAm() {
        LocalDateTime ist = Time.toIst(Time.now());
        LocalDateTime nine = ist.toLocalDate().atTime(LocalTime.of(9, 0));
        if (!ist.isBefore(nine)) nine = nine.plusDays(1);
        return Time.istToUtc(nine);
    }
}
