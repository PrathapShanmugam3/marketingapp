package com.marketingpanel.crm;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.auth.CaptchaVerifier;
import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import com.marketingpanel.jobs.JobService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** P18 Lead form builder and the public submission endpoint. */
@Service
public class FormService {
    private static final Set<String> RESERVED = Set.of("api", "admin", "f", "r", "l", "o", "p", "pb", "s", "files", "webhooks", "internal",
            "login", "signup", "www", "app", "assets", "static", "dev", "ws", "dashboard", "settings", "support", "health");
    private static final Pattern KEY = Pattern.compile("^[a-z0-9_]{1,30}$");
    private final Db db;
    private final PlanService plans;
    private final ContactService contacts;
    private final LeadService leads;
    private final CaptchaVerifier captcha;
    private final RateLimiter limiter;
    private final JobService jobs;
    private final ApplicationEventPublisher events;
    private final AuditService audit;
    private final Settings settings;

    public FormService(Db db, PlanService plans, ContactService contacts, LeadService leads, CaptchaVerifier captcha, RateLimiter limiter,
                       JobService jobs, ApplicationEventPublisher events, AuditService audit, Settings settings) {
        this.db = db;
        this.plans = plans;
        this.contacts = contacts;
        this.leads = leads;
        this.captcha = captcha;
        this.limiter = limiter;
        this.jobs = jobs;
        this.events = events;
        this.audit = audit;
        this.settings = settings;
    }

    public List<Map<String, Object>> list(long ws) {
        return db.list("select * from lead_forms where workspace_id=:ws order by id desc", "ws", ws);
    }

    public Map<String, Object> get(long ws, long id) {
        return db.one("select * from lead_forms where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Form"));
    }

    private List<Map<String, Object>> normaliseFields(List<CrmDtos.FormField> in) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        // Name and Mobile are always included (SRS P18).
        out.add(Map.of("key", "name", "label", "Name", "type", "text", "required", true));
        out.add(Map.of("key", "phone", "label", "Mobile", "type", "text", "required", true));
        keys.add("name");
        keys.add("phone");
        for (CrmDtos.FormField f : in) {
            if (!KEY.matcher(f.key()).matches()) throw ApiException.invalid("fields", "form.field.key", "Field keys use lowercase letters, numbers and _.");
            if (!keys.add(f.key())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", f.key());
            m.put("label", Norm.plain(f.label()));
            m.put("type", f.type());
            m.put("required", f.required());
            if ("select".equals(f.type())) {
                if (f.options() == null || f.options().isEmpty() || f.options().size() > 20)
                    throw ApiException.invalid("fields", "form.field.options", "Add 1-20 options for a select field.");
                m.put("options", f.options().stream().map(Norm::plain).toList());
            }
            out.add(m);
        }
        if (out.size() > 10) throw ApiException.invalid("fields", "form.fields.max", "A form can have up to 10 fields.");
        return out;
    }

    private void checkRefs(long ws, CrmDtos.FormRequest r) {
        if (r.autoReplyTemplateId() != null && !db.exists("select 1 from wa_templates where id=:t and workspace_id=:ws and status='APPROVED'", "t", r.autoReplyTemplateId(), "ws", ws))
            throw ApiException.invalid("autoReplyTemplateId", "form.template", "Choose an approved template.");
        if (r.defaultTagId() != null && !db.exists("select 1 from tags where id=:t and workspace_id=:ws", "t", r.defaultTagId(), "ws", ws))
            throw ApiException.invalid("defaultTagId", "form.tag", "Choose an existing tag.");
    }

    public Map<String, Object> create(long ws, CrmDtos.FormRequest r) {
        plans.requireFeature(ws, "forms");
        if (RESERVED.contains(r.slug()) || db.exists("select 1 from lead_forms where slug=:s", "s", r.slug()))
            throw ApiException.duplicate("slug", "form.slug", "This link is taken. Try another.");
        checkRefs(ws, r);
        long id = db.insertInto("lead_forms", "workspace_id", ws, "name", r.name(), "slug", r.slug(), "title", r.title(), "description", r.description(),
                "fields_json", Json.write(normaliseFields(r.fields())), "consent_text", r.consentText(),
                "success_message", r.successMessage() == null || r.successMessage().isBlank() ? "Thank you! We will contact you soon." : r.successMessage(),
                "auto_reply_template_id", r.autoReplyTemplateId(), "default_tag_id", r.defaultTagId(), "is_active", r.active() == null || r.active());
        audit.log("FORM_CREATED", "lead_forms", id);
        return get(ws, id);
    }

    public Map<String, Object> update(long ws, long id, CrmDtos.FormRequest r) {
        get(ws, id);
        if (db.exists("select 1 from lead_forms where slug=:s and id<>:id", "s", r.slug(), "id", id) || RESERVED.contains(r.slug()))
            throw ApiException.duplicate("slug", "form.slug", "This link is taken. Try another.");
        checkRefs(ws, r);
        db.update("update lead_forms set name=:n, slug=:s, title=:t, description=:d, fields_json=:f, consent_text=:c, success_message=:m, " +
                        "auto_reply_template_id=:a, default_tag_id=:g, is_active=:act where id=:id and workspace_id=:ws",
                "n", r.name(), "s", r.slug(), "t", r.title(), "d", r.description(), "f", Json.write(normaliseFields(r.fields())), "c", r.consentText(),
                "m", r.successMessage() == null || r.successMessage().isBlank() ? "Thank you! We will contact you soon." : r.successMessage(),
                "a", r.autoReplyTemplateId(), "g", r.defaultTagId(), "act", r.active() == null || r.active(), "id", id, "ws", ws);
        return get(ws, id);
    }

    public void delete(long ws, long id) {
        get(ws, id);
        db.update("update landing_pages set lead_form_id=null where lead_form_id=:id", "id", id);
        db.update("delete from lead_forms where id=:id and workspace_id=:ws", "id", id, "ws", ws);
    }

    // ---------------------------------------------------------------- public

    public Optional<Row> bySlug(String slug) {
        return db.row("select * from lead_forms where slug=:s and is_active=1", "s", slug);
    }

    public record Submission(Map<String, String> fields, boolean consent, String website, String captchaToken) {}

    /** Public submit: CAPTCHA + honeypot, 5 per IP per hour, consent mandatory. Returns the success message. */
    public String submit(String slug, Submission s, String ip) {
        limiter.check("form:" + ip, 5, Duration.ofHours(1));
        Row form = bySlug(slug).orElseThrow(() -> ApiException.notFound("Form"));
        if (s.website() != null && !s.website().isBlank()) return form.s("success_message"); // honeypot: silently accept
        captcha.verify(s.captchaToken(), ip, "captchaToken");
        long ws = form.l("workspace_id");
        List<FieldErr> errs = new ArrayList<>();
        Map<String, String> in = s.fields() == null ? Map.of() : s.fields();
        Map<String, String> custom = new LinkedHashMap<>();
        for (JsonNode f : Json.tree(form.s("fields_json"))) {
            String key = f.get("key").asText();
            String val = Norm.text(in.get(key));
            if (f.path("required").asBoolean() && val == null) errs.add(new FieldErr(key, "vr.required", "This field is required."));
            if (val != null && val.length() > 200) errs.add(new FieldErr(key, "vr.size", "Value is too long."));
            if ("number".equals(f.path("type").asText()) && val != null && !val.matches("^-?\\d+(\\.\\d+)?$")) errs.add(new FieldErr(key, "form.number", "Enter a number."));
            if (!Set.of("name", "phone", "email", "city", "dob").contains(key) && val != null) custom.put(key, Norm.plain(val));
        }
        String name = Norm.name(Norm.plain(in.get("name")));
        String phone = Phones.e164(in.get("phone"));
        if (name == null || !name.matches("^[\\p{L}\\p{M} .'&-]{2,100}$")) errs.add(new FieldErr("name", "vr.name.invalid", "Enter a valid name (2-100 letters)."));
        if (phone == null) errs.add(new FieldErr("phone", "vr.mobile.invalid", "Enter a valid 10-digit mobile number."));
        String email = Norm.email(in.get("email"));
        if (email != null && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) errs.add(new FieldErr("email", "vr.email.invalid", "Enter a valid email address."));
        if (!s.consent()) errs.add(new FieldErr("consent", "form.consent", "Please tick the consent box."));
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        custom.keySet().removeIf(k -> custom.size() > 10);

        long contactId = upsertContact(ws, name, phone, email, Norm.name(in.get("city")), custom);
        if (form.lo("default_tag_id") != null) {
            int n = db.update("insert ignore into contact_tags (contact_id, tag_id) values (:c,:t)", "c", contactId, "t", form.l("default_tag_id"));
            if (n > 0) events.publishEvent(new Events.TagAdded(ws, contactId, form.l("default_tag_id")));
        }
        // Same mobile within 24 h updates the existing lead instead of creating another.
        Optional<Row> lead = db.row("select id from leads where workspace_id=:ws and contact_id=:c and lead_form_id=:f and created_at > :t and deleted_at is null order by id desc limit 1",
                "ws", ws, "c", contactId, "f", form.l("id"), "t", Time.now().minusHours(24));
        String summary = "Submitted via form '" + form.s("name") + "'" + (custom.isEmpty() ? "" : ": " + custom);
        if (lead.isPresent()) {
            leads.addActivity(lead.get().l("id"), "NOTE", summary, null);
        } else {
            long leadId = db.insertInto("leads", "workspace_id", ws, "contact_id", contactId, "source", "FORM", "lead_form_id", form.l("id"), "status", "NEW", "requirement",
                    summary.length() > 1000 ? summary.substring(0, 1000) : summary);
            leads.addActivity(leadId, "STATUS_CHANGE", "Lead created from form '" + form.s("name") + "'", null);
        }
        db.update("update lead_forms set submissions_count=submissions_count+1 where id=:id", "id", form.l("id"));
        if (form.lo("auto_reply_template_id") != null) {
            jobs.enqueue("TEMPLATE_DIRECT", Map.of("workspaceId", ws, "contactId", contactId, "templateId", form.l("auto_reply_template_id")));
        }
        events.publishEvent(new Events.FormSubmitted(ws, contactId, form.l("id")));
        return form.s("success_message");
    }

    private long upsertContact(long ws, String name, String phone, String email, String city, Map<String, String> custom) {
        Optional<Row> ex = db.row("select * from contacts where workspace_id=:ws and phone=:p", "ws", ws, "p", phone);
        String cf = custom.isEmpty() ? null : Json.write(custom);
        if (ex.isPresent()) {
            long id = ex.get().l("id");
            boolean deleted = ex.get().t("deleted_at") != null;
            if (deleted) plans.checkContactLimit(ws, 1);
            db.update("update contacts set full_name=if(:del=1,:n,full_name), email=coalesce(email,:e), city=coalesce(city,:c), " +
                            "custom_fields=coalesce(:cf,custom_fields), deleted_at=null, opt_in_status='OPTED_IN', opt_in_source='FORM', " +
                            "opted_in_at=:now, opted_out_at=null where id=:id",
                    "del", deleted ? 1 : 0, "n", name, "e", email, "c", city, "cf", cf, "now", Time.now(), "id", id);
            return id;
        }
        try {
            plans.checkContactLimit(ws, 1);
        } catch (ApiException e) {
            throw new ApiException(ErrorCode.PLAN_LIMIT_REACHED, "This form is not accepting responses right now.");
        }
        long id = db.insertInto("contacts", "workspace_id", ws, "full_name", name, "phone", phone, "email", email, "city", city,
                "opt_in_status", "OPTED_IN", "opt_in_source", "FORM", "opted_in_at", Time.now(), "custom_fields", cf);
        events.publishEvent(new Events.ContactCreated(ws, id));
        return id;
    }
}
