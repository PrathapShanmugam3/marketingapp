package com.marketingpanel.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import com.marketingpanel.jobs.JobHandler;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** P13 Template builder: Meta limits live here so they can be re-checked against Meta docs before release. */
@Service
public class TemplateService implements JobHandler {
    static final Pattern VAR = Pattern.compile("\\{\\{(\\d+)}}");
    static final Pattern NAME = Pattern.compile("^[a-z0-9_]{3,512}$");
    static final String DEFAULT_FOOTER = "Reply STOP to unsubscribe";

    private final Db db;
    private final WaGateway gw;
    private final AuditService audit;
    private final NotificationService notifications;

    public TemplateService(Db db, WaGateway gw, AuditService audit, NotificationService notifications) {
        this.db = db;
        this.gw = gw;
        this.audit = audit;
        this.notifications = notifications;
    }

    public record TemplateRequest(String name, String category, String language, String headerType, String headerText, String headerMediaUrl,
                                  String body, List<String> sampleValues, String footer, List<Map<String, Object>> buttons, Boolean submit) {}

    /** Variables must be {{1}}..{{n}} in order, with no gaps, not at the very start or end, and never side by side. */
    static int validateVariables(String text, String field, List<FieldErr> errs) {
        Matcher m = VAR.matcher(text);
        int expected = 1;
        int count = 0;
        int lastEnd = -10;
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n != expected) {
                errs.add(new FieldErr(field, "template.var.order", "Variables must be numbered {{1}}, {{2}}… in order."));
                return count;
            }
            if (m.start() == 0 || m.end() == text.length()) {
                errs.add(new FieldErr(field, "template.var.edge", "A variable cannot be at the very start or end of the text."));
                return count;
            }
            if (m.start() == lastEnd) {
                errs.add(new FieldErr(field, "template.var.adjacent", "Two variables cannot be side by side."));
                return count;
            }
            lastEnd = m.end();
            expected++;
            count++;
        }
        return count;
    }

    private Map<String, Object> validate(TemplateRequest r, long ws, Long exceptId) {
        List<FieldErr> errs = new ArrayList<>();
        String name = r.name() == null ? "" : r.name().trim();
        if (!NAME.matcher(name).matches()) errs.add(new FieldErr("name", "template.name", "Use lowercase letters, numbers and _ only."));
        if (!List.of("MARKETING", "UTILITY").contains(r.category())) errs.add(new FieldErr("category", "template.category", "Choose a category."));
        if (!List.of("en", "ta").contains(r.language())) errs.add(new FieldErr("language", "template.language", "Choose a language."));
        String headerType = r.headerType() == null || r.headerType().isBlank() ? "NONE" : r.headerType();
        if (!List.of("NONE", "TEXT", "IMAGE", "VIDEO", "DOCUMENT").contains(headerType)) errs.add(new FieldErr("headerType", "template.header", "Choose a header type."));
        String headerText = Norm.plain(r.headerText());
        if ("TEXT".equals(headerType)) {
            if (headerText == null || headerText.isBlank() || headerText.length() > 60)
                errs.add(new FieldErr("headerText", "template.header.text", "Header text can be up to 60 characters."));
            else if (validateVariables(headerText, "headerText", errs) > 1)
                errs.add(new FieldErr("headerText", "template.header.vars", "Header text can have at most 1 variable."));
        } else if (!"NONE".equals(headerType) && (r.headerMediaUrl() == null || r.headerMediaUrl().isBlank())) {
            errs.add(new FieldErr("headerMediaUrl", "template.header.media", "Upload the header file."));
        }
        String body = r.body() == null ? "" : r.body().trim();
        if (body.isEmpty() || body.length() > 1024) errs.add(new FieldErr("body", "template.body", "Body must be 1 to 1,024 characters."));
        int vars = body.isEmpty() ? 0 : validateVariables(body, "body", errs);
        List<String> samples = r.sampleValues() == null ? List.of() : r.sampleValues();
        if (vars > 0) {
            if (samples.size() < vars || samples.subList(0, vars).stream().anyMatch(s -> s == null || s.isBlank()))
                errs.add(new FieldErr("sampleValues", "template.samples", "Add a sample for each variable."));
            else if (samples.stream().anyMatch(s -> s != null && s.length() > 100))
                errs.add(new FieldErr("sampleValues", "template.samples.len", "Samples can be up to 100 characters."));
        }
        String footer = Norm.plain(r.footer());
        if (footer != null && (footer.length() > 60 || VAR.matcher(footer).find()))
            errs.add(new FieldErr("footer", "template.footer", "Footer can be up to 60 characters, with no variables."));
        List<Map<String, Object>> buttons = r.buttons() == null ? List.of() : r.buttons();
        int urls = 0, phones = 0;
        boolean optOutButton = false;
        List<Map<String, Object>> cleanButtons = new ArrayList<>();
        if (buttons.size() > 10) errs.add(new FieldErr("buttons", "template.buttons.max", "A template can have up to 10 buttons."));
        for (Map<String, Object> b : buttons) {
            String type = String.valueOf(b.get("type"));
            String text = Norm.plain(String.valueOf(b.getOrDefault("text", "")));
            if (text == null || text.isBlank() || text.length() > 25) {
                errs.add(new FieldErr("buttons", "template.button.text", "Button text can be up to 25 characters."));
                continue;
            }
            Map<String, Object> cb = new LinkedHashMap<>();
            cb.put("type", type);
            cb.put("text", text);
            switch (type) {
                case "QUICK_REPLY" -> {
                    if (text.toLowerCase().matches(".*(stop|unsubscribe|opt.?out).*")) optOutButton = true;
                }
                case "URL" -> {
                    String url = String.valueOf(b.getOrDefault("url", ""));
                    if (++urls > 2) errs.add(new FieldErr("buttons", "template.button.url.max", "At most 2 URL buttons."));
                    if (!UrlGuard.isPublicHttps(url.replace("{{1}}", "x"), 2000)) errs.add(new FieldErr("buttons", "template.button.url", "Enter a valid https:// link."));
                    cb.put("url", url);
                    if (url.contains("{{1}}")) cb.put("example", List.of("https://example.com/sample"));
                }
                case "PHONE_NUMBER" -> {
                    if (++phones > 1) errs.add(new FieldErr("buttons", "template.button.phone.max", "At most 1 phone button."));
                    String ph = Phones.e164(String.valueOf(b.getOrDefault("phone_number", b.getOrDefault("phone", ""))));
                    if (ph == null) errs.add(new FieldErr("buttons", "template.button.phone", "Enter a valid mobile number."));
                    cb.put("phone_number", ph);
                }
                default -> errs.add(new FieldErr("buttons", "template.button.type", "Unknown button type."));
            }
            cleanButtons.add(cb);
        }
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        // Marketing templates always carry an opt-out path (SRS P13).
        if ("MARKETING".equals(r.category()) && !optOutButton) {
            if (footer == null || footer.isBlank()) footer = DEFAULT_FOOTER;
            else if (!footer.toLowerCase().contains("stop")) {
                String combined = footer + " | " + DEFAULT_FOOTER;
                footer = combined.length() <= 60 ? combined : DEFAULT_FOOTER;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("category", r.category());
        out.put("language", r.language());
        out.put("header_type", headerType);
        out.put("header_text", "TEXT".equals(headerType) ? headerText : null);
        out.put("header_media_url", "TEXT".equals(headerType) || "NONE".equals(headerType) ? null : r.headerMediaUrl());
        out.put("body", body);
        out.put("footer", footer == null || footer.isBlank() ? null : footer);
        out.put("buttons_json", cleanButtons.isEmpty() ? null : Json.write(cleanButtons));
        out.put("variable_count", vars);
        out.put("sample_values_json", vars == 0 ? null : Json.write(samples.subList(0, vars)));
        return out;
    }

    public Map<String, Object> create(long ws, long userId, TemplateRequest r) {
        Row acct = gw.account(ws);
        if (acct == null) throw new ApiException(ErrorCode.WHATSAPP_NOT_CONNECTED, "Connect your WhatsApp number first.");
        Map<String, Object> cols = validate(r, ws, null);
        if (db.exists("select 1 from wa_templates where wa_account_id=:a and name=:n and language=:l", "a", acct.l("id"), "n", cols.get("name"), "l", cols.get("language")))
            throw ApiException.duplicate("name", "template.exists", "A template with this name and language already exists.");
        long id = db.insertInto("wa_templates", "workspace_id", ws, "wa_account_id", acct.l("id"), "name", cols.get("name"), "language", cols.get("language"),
                "category", cols.get("category"), "header_type", cols.get("header_type"), "header_text", cols.get("header_text"),
                "header_media_url", cols.get("header_media_url"), "body", cols.get("body"), "footer", cols.get("footer"), "buttons_json", cols.get("buttons_json"),
                "variable_count", cols.get("variable_count"), "sample_values_json", cols.get("sample_values_json"), "created_by", userId);
        audit.log("TEMPLATE_CREATED", "wa_templates", id);
        if (Boolean.TRUE.equals(r.submit())) submit(ws, id);
        return get(ws, id);
    }

    public Map<String, Object> update(long ws, long id, TemplateRequest r) {
        Row cur = load(ws, id);
        if (!List.of("DRAFT", "REJECTED").contains(cur.s("status"))) throw ApiException.state("Only draft or rejected templates can be edited.");
        Map<String, Object> cols = validate(r, ws, id);
        if (db.exists("select 1 from wa_templates where wa_account_id=:a and name=:n and language=:l and id<>:id", "a", cur.l("wa_account_id"), "n", cols.get("name"), "l", cols.get("language"), "id", id))
            throw ApiException.duplicate("name", "template.exists", "A template with this name and language already exists.");
        db.updateTable("wa_templates", "id", id, cols, "workspace_id", ws);
        if (Boolean.TRUE.equals(r.submit())) submit(ws, id);
        return get(ws, id);
    }

    public Map<String, Object> submit(long ws, long id) {
        Row t = load(ws, id);
        if (!List.of("DRAFT", "REJECTED").contains(t.s("status"))) throw ApiException.state("This template was already submitted.");
        Row acct = gw.connected(ws);
        List<String> samples = t.s("sample_values_json") == null ? List.of() : Json.list(t.s("sample_values_json")).stream().map(String::valueOf).toList();
        List<Map<String, Object>> buttons = new ArrayList<>();
        if (t.s("buttons_json") != null) {
            for (Object o : Json.list(t.s("buttons_json"))) {
                @SuppressWarnings("unchecked") Map<String, Object> b = (Map<String, Object>) o;
                buttons.add(b);
            }
        }
        TemplateSpec spec = new TemplateSpec(t.s("name"), t.s("language"), t.s("category"), t.s("header_type"), t.s("header_text"), t.s("header_media_url"),
                t.s("body"), t.s("footer"), buttons, samples);
        try {
            String metaId = gw.client().submitTemplate(gw.token(acct), acct.s("waba_id"), spec);
            db.update("update wa_templates set status='PENDING', meta_template_id=:m, rejection_reason=null where id=:id", "m", metaId, "id", id);
        } catch (WaException e) {
            throw ApiException.gateway("Meta could not accept this template: " + e.getMessage());
        }
        audit.log("TEMPLATE_SUBMITTED", "wa_templates", id);
        return get(ws, id);
    }

    public void delete(long ws, long id) {
        load(ws, id);
        if (db.exists("select 1 from campaigns where template_id=:id and status in ('SCHEDULED','QUEUED','SENDING','PAUSED')", "id", id))
            throw ApiException.state("This template is used by an active campaign.");
        try {
            db.update("delete from wa_templates where id=:id and workspace_id=:ws", "id", id, "ws", ws);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            db.update("update wa_templates set status='DISABLED' where id=:id", "id", id);
        }
    }

    Row load(long ws, long id) {
        return db.row("select * from wa_templates where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Template"));
    }

    public Map<String, Object> get(long ws, long id) {
        return load(ws, id).api();
    }

    public List<Map<String, Object>> list(long ws, String status, String category) {
        StringBuilder sb = new StringBuilder("select * from wa_templates where workspace_id=:ws");
        List<Object> kv = new ArrayList<>(List.of("ws", ws));
        if (status != null && !status.isBlank()) {
            sb.append(" and status=:st");
            kv.add("st");
            kv.add(status);
        }
        if (category != null && !category.isBlank()) {
            sb.append(" and category=:ct");
            kv.add("ct");
            kv.add(category);
        }
        return db.list(sb + " order by id desc", kv.toArray());
    }

    /** Applies a status change coming from the Meta webhook (message_template_status_update). */
    public void applyStatus(String metaId, String name, String event, String reason) {
        String status = switch (event == null ? "" : event.toUpperCase()) {
            case "APPROVED" -> "APPROVED";
            case "REJECTED" -> "REJECTED";
            case "PAUSED" -> "PAUSED";
            case "DISABLED" -> "DISABLED";
            case "PENDING", "IN_APPEAL" -> "PENDING";
            default -> null;
        };
        if (status == null) return;
        List<Row> rows = metaId != null && !metaId.isBlank()
                ? db.rows("select * from wa_templates where meta_template_id=:m", "m", metaId)
                : db.rows("select * from wa_templates where name=:n", "n", name);
        for (Row t : rows) {
            String why = "REJECTED".equals(status) ? (reason == null || "NONE".equals(reason) ? "Rejected by Meta." : reason) : null;
            db.update("update wa_templates set status=:s, rejection_reason=:r where id=:id", "s", status, "r", why, "id", t.l("id"));
            notifications.notifyOwners(t.l("workspace_id"), "TEMPLATE_" + status, "Template " + t.s("name") + " " + status.toLowerCase(),
                    why, "/templates");
        }
    }

    /** Pulls statuses from Meta for PENDING templates; a safety net if a webhook is missed. */
    public int sync(long ws) {
        Row acct = gw.account(ws);
        if (acct == null) return 0;
        int n = 0;
        try {
            for (WhatsAppClient.TemplateStatus s : gw.client().listTemplates(gw.token(acct), acct.s("waba_id"))) {
                applyStatus(s.metaId(), s.name(), s.status(), s.reason());
                n++;
            }
        } catch (WaException ignored) {
            // retried by the next scheduled sync
        }
        return n;
    }

    // mock approval job used in sandbox mode
    @Override
    public String type() {
        return "WA_MOCK_TEMPLATE";
    }

    @Override
    public void handle(JsonNode p) {
        applyStatus(p.get("metaId").asText(), p.get("name").asText(), p.get("approve").asBoolean() ? "APPROVED" : "REJECTED",
                p.get("approve").asBoolean() ? null : "Sandbox rejection (template name contains 'reject').");
    }
}
