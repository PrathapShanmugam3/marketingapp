package com.marketingpanel.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import com.marketingpanel.jobs.JobHandler;
import com.marketingpanel.jobs.JobService;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;

/** P17 Review Booster. No review gating: every customer gets the same Google link (Google policy). */
@Service
public class ReviewService implements JobHandler {
    private static final Set<String> HOSTS = Set.of("g.page", "maps.app.goo.gl", "search.google.com", "google.com", "www.google.com", "maps.google.com");
    private final Db db;
    private final Settings settings;
    private final JobService jobs;
    private final FileStore store;
    private final AuditService audit;

    public ReviewService(Db db, Settings settings, JobService jobs, FileStore store, AuditService audit) {
        this.db = db;
        this.settings = settings;
        this.jobs = jobs;
        this.store = store;
        this.audit = audit;
    }

    public record SettingsRequest(String googleReviewUrl, Long templateId, Boolean autoSend, Long triggerTagId, Integer delayMinutes) {}

    public Map<String, Object> settings(long ws) {
        return db.one("select * from review_settings where workspace_id=:ws", "ws", ws).orElse(new LinkedHashMap<>());
    }

    public Map<String, Object> saveSettings(long ws, SettingsRequest r) {
        String url = r.googleReviewUrl() == null ? "" : r.googleReviewUrl().trim();
        List<FieldErr> errs = new ArrayList<>();
        String host = UrlGuard.host(url);
        if (!UrlGuard.isPublicHttps(url, 500) || host == null || !HOSTS.contains(host.toLowerCase()))
            errs.add(new FieldErr("googleReviewUrl", "review.url", "Paste your Google review link."));
        if (r.templateId() == null) {
            errs.add(new FieldErr("templateId", "review.template", "Choose a template that includes the review link."));
        } else {
            Row t = db.row("select * from wa_templates where id=:id and workspace_id=:ws and status='APPROVED' and category in ('MARKETING','UTILITY')", "id", r.templateId(), "ws", ws).orElse(null);
            boolean hasUrlButton = t != null && t.s("buttons_json") != null && t.s("buttons_json").contains("\"URL\"");
            if (t == null || (t.i("variable_count") < 2 && !hasUrlButton))
                errs.add(new FieldErr("templateId", "review.template", "Choose a template that includes the review link."));
        }
        boolean auto = Boolean.TRUE.equals(r.autoSend());
        int delay = r.delayMinutes() == null ? 60 : r.delayMinutes();
        if (auto) {
            if (r.triggerTagId() == null || !db.exists("select 1 from tags where id=:t and workspace_id=:ws", "t", r.triggerTagId(), "ws", ws))
                errs.add(new FieldErr("triggerTagId", "review.tag", "Auto-send needs a trigger tag."));
            if (delay < 15 || delay > 10080) errs.add(new FieldErr("delayMinutes", "review.delay", "Delay must be between 15 minutes and 7 days."));
        }
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        db.update("insert into review_settings (workspace_id, google_review_url, template_id, auto_send, trigger_tag_id, delay_minutes) values (:ws,:u,:t,:a,:g,:d) " +
                        "on duplicate key update google_review_url=:u, template_id=:t, auto_send=:a, trigger_tag_id=:g, delay_minutes=:d",
                "ws", ws, "u", url, "t", r.templateId(), "a", auto, "g", auto ? r.triggerTagId() : null, "d", delay);
        audit.log("REVIEW_SETTINGS_SAVED", "review_settings", ws);
        return settings(ws);
    }

    private Row settingsRow(long ws) {
        return db.row("select * from review_settings where workspace_id=:ws", "ws", ws)
                .orElseThrow(() -> ApiException.state("Set up your Google review link first."));
    }

    public Map<String, Object> request(long ws, long contactId) {
        Row s = settingsRow(ws);
        Row c = db.row("select * from contacts where id=:id and workspace_id=:ws and deleted_at is null", "id", contactId, "ws", ws).orElseThrow(() -> ApiException.notFound("Contact"));
        if (!"OPTED_IN".equals(c.s("opt_in_status"))) throw ApiException.invalid("contactId", "review.optin", "This contact has not opted in to WhatsApp messages.");
        if (db.exists("select 1 from review_requests where workspace_id=:ws and contact_id=:c and channel='WHATSAPP' and created_at > :t", "ws", ws, "c", contactId, "t", Time.now().minusDays(30)))
            throw ApiException.invalid("contactId", "review.recent", "A review request was already sent in the last 30 days.");
        String code = uniqueCode();
        long id = db.insertInto("review_requests", "workspace_id", ws, "contact_id", contactId, "link_code", code, "channel", "WHATSAPP", "sent_at", Time.now());
        Row tpl = db.row("select * from wa_templates where id=:id", "id", s.l("template_id")).orElseThrow();
        String link = settings.publicBaseUrl + "/r/" + code;
        List<String> vars = new ArrayList<>(List.of(c.s("full_name").trim().split("\\s+")[0], link));
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("workspaceId", ws);
        job.put("contactId", contactId);
        job.put("templateId", tpl.l("id"));
        job.put("variables", vars.subList(0, Math.min(vars.size(), tpl.i("variable_count"))));
        job.put("urlSuffix", code);
        jobs.enqueue("TEMPLATE_DIRECT", job);
        audit.log("REVIEW_REQUEST_SENT", "review_requests", id);
        return Map.of("id", id, "link", link);
    }

    private String uniqueCode() {
        String code;
        do {
            code = Ids.randomCode(10);
        } while (db.exists("select 1 from review_requests where link_code=:c", "c", code));
        return code;
    }

    /** GET /r/{code}: records the first open and redirects to the Google link. */
    public String redirect(String code) {
        Row r = db.row("select rr.*, s.google_review_url from review_requests rr join review_settings s on s.workspace_id=rr.workspace_id where rr.link_code=:c", "c", code)
                .orElseThrow(() -> ApiException.notFound("Link"));
        if (r.t("clicked_at") == null) db.update("update review_requests set clicked_at=:now where id=:id and clicked_at is null", "now", Time.now(), "id", r.l("id"));
        return r.s("google_review_url");
    }

    // ---------------------------------------------------------- auto-send

    @EventListener
    public void onTagAdded(Events.TagAdded e) {
        Row s = db.row("select * from review_settings where workspace_id=:ws and auto_send=1 and trigger_tag_id=:t", "ws", e.workspaceId(), "t", e.tagId()).orElse(null);
        if (s == null) return;
        jobs.enqueue("REVIEW_AUTO", Map.of("workspaceId", e.workspaceId(), "contactId", e.contactId()), Time.now().plusMinutes(s.i("delay_minutes")));
    }

    @Override
    public String type() {
        return "REVIEW_AUTO";
    }

    @Override
    public void handle(JsonNode p) {
        try {
            request(p.get("workspaceId").asLong(), p.get("contactId").asLong());
        } catch (ApiException ignored) {
            // not opted in, already asked within 30 days, or settings removed: skip silently
        }
    }

    // ---------------------------------------------------------- QR + poster

    private String qrLink(long ws) {
        settingsRow(ws);
        String code = db.scalar("select link_code from review_requests where workspace_id=:ws and channel='QR' order by id desc limit 1", String.class, "ws", ws);
        if (code == null) {
            code = uniqueCode();
            db.insertInto("review_requests", "workspace_id", ws, "link_code", code, "channel", "QR");
        }
        return settings.publicBaseUrl + "/r/" + code;
    }

    public static byte[] qrPng(String text, int size) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, Map.of(EncodeHintType.MARGIN, 1));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(m, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] qr(long ws) {
        return qrPng(qrLink(ws), 512);
    }

    /** QR poster (A4 or A5 PDF) with the business logo and name. */
    public byte[] poster(long ws, String size) {
        Row w = db.row("select * from workspaces where id=:id", "id", ws).orElseThrow();
        byte[] qr = qrPng(qrLink(ws), 800);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document("A5".equalsIgnoreCase(size) ? PageSize.A5 : PageSize.A4, 48, 48, 60, 48);
            PdfWriter.getInstance(doc, out);
            doc.open();
            if (w.s("logo_url") != null) {
                byte[] logo = store.read(w.s("logo_url"));
                if (logo != null) {
                    com.lowagie.text.Image img = com.lowagie.text.Image.getInstance(logo);
                    img.scaleToFit(90, 90);
                    img.setAlignment(Element.ALIGN_CENTER);
                    doc.add(img);
                }
            }
            Paragraph name = new Paragraph(w.s("name"), new Font(Font.HELVETICA, 26, Font.BOLD));
            name.setAlignment(Element.ALIGN_CENTER);
            name.setSpacingBefore(14);
            doc.add(name);
            Paragraph t = new Paragraph("Enjoyed your visit? Scan to review us on Google", new Font(Font.HELVETICA, 16));
            t.setAlignment(Element.ALIGN_CENTER);
            t.setSpacingBefore(10);
            t.setSpacingAfter(20);
            doc.add(t);
            com.lowagie.text.Image q = com.lowagie.text.Image.getInstance(qr);
            q.scaleToFit(300, 300);
            q.setAlignment(Element.ALIGN_CENTER);
            doc.add(q);
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // --------------------------------------------------------------- stats

    public Map<String, Object> stats(long ws) {
        long sent = db.count("select count(*) from review_requests where workspace_id=:ws and channel='WHATSAPP'", "ws", ws);
        long clicked = db.count("select count(*) from review_requests where workspace_id=:ws and channel='WHATSAPP' and clicked_at is not null", "ws", ws);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestsSent", sent);
        out.put("clicked", clicked);
        out.put("clickRate", sent == 0 ? 0 : Math.round(clicked * 1000.0 / sent) / 10.0);
        out.put("history", db.list("select captured_on, rating, review_count, source from review_stats where workspace_id=:ws order by captured_on desc limit 90", "ws", ws));
        out.put("latest", db.one("select captured_on, rating, review_count from review_stats where workspace_id=:ws order by captured_on desc limit 1", "ws", ws).orElse(null));
        return out;
    }

    public void recordManual(long ws, double rating, int count) {
        if (rating < 1.0 || rating > 5.0) throw ApiException.invalid("rating", "review.rating", "Rating must be between 1.0 and 5.0.");
        if (count < 0) throw ApiException.invalid("reviewCount", "review.count", "Enter a valid number.");
        db.update("insert into review_stats (workspace_id, captured_on, rating, review_count, source) values (:ws,:d,:r,:c,'MANUAL') " +
                "on duplicate key update rating=:r, review_count=:c, source='MANUAL'", "ws", ws, "d", LocalDate.now(Time.IST), "r", Math.round(rating * 10) / 10.0, "c", count);
    }

    public List<Map<String, Object>> requests(long ws) {
        return db.list("select rr.id, rr.channel, rr.sent_at, rr.clicked_at, rr.created_at, c.full_name from review_requests rr left join contacts c on c.id=rr.contact_id " +
                "where rr.workspace_id=:ws and rr.channel='WHATSAPP' order by rr.id desc limit 100", "ws", ws);
    }
}
