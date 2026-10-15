package com.marketingpanel.growth;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/** P30 Landing page builder: mobile-first pages from ordered blocks, on the platform domain or a verified custom domain. */
@Service
public class PageService {
    static final Set<String> BLOCKS = Set.of("hero", "text", "image", "offer", "countdown", "form", "whatsapp", "appbadges", "map");
    static final Set<String> CTA = Set.of("form", "whatsapp", "appbadges");
    static final Set<String> RESERVED = Set.of("api", "admin", "login", "signup", "static", "assets", "files", "webhooks", "internal", "health", "_domain");
    private static final Pattern PATH = Pattern.compile("^[a-z0-9-]{1,100}$");
    private final Db db;
    private final PlanService plans;
    private final Settings settings;
    private final AuditService audit;

    public PageService(Db db, PlanService plans, Settings settings, AuditService audit) {
        this.db = db;
        this.plans = plans;
        this.settings = settings;
        this.audit = audit;
    }

    public record PageRequest(String title, Long domainId, String path, JsonNode blocks, Long leadFormId, String seoTitle, String seoDescription) {}

    public List<Map<String, Object>> list(long ws) {
        List<Map<String, Object>> rows = db.list("select p.id, p.title, p.path, p.status, p.domain_id, d.domain, p.published_at, p.updated_at from landing_pages p " +
                "left join custom_domains d on d.id=p.domain_id where p.workspace_id=:ws order by p.id desc", "ws", ws);
        for (Map<String, Object> m : rows) m.put("url", publicUrl(ws, (String) m.get("domain"), (String) m.get("path")));
        return rows;
    }

    public String publicUrl(long ws, String domain, String path) {
        return domain == null ? settings.publicBaseUrl + "/p/" + ws + "/" + path : "https://" + domain + "/" + path;
    }

    public Map<String, Object> get(long ws, long id) {
        Map<String, Object> m = db.one("select p.*, d.domain from landing_pages p left join custom_domains d on d.id=p.domain_id where p.id=:id and p.workspace_id=:ws",
                "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Page"));
        m.put("url", publicUrl(ws, (String) m.get("domain"), (String) m.get("path")));
        return m;
    }

    private List<Object> clean(long ws, PageRequest r, Long exceptId) {
        List<FieldErr> errs = new ArrayList<>();
        String title = Norm.plain(Norm.name(r.title()));
        if (title == null || title.length() < 3 || title.length() > 100) errs.add(new FieldErr("title", "page.title", "Enter a page title."));
        String path = r.path() == null ? "" : r.path().trim();
        if (!PATH.matcher(path).matches() || RESERVED.contains(path)) errs.add(new FieldErr("path", "page.path", "This page address is already used."));
        Long domainId = r.domainId();
        if (domainId != null && !db.exists("select 1 from custom_domains where id=:id and workspace_id=:ws and purpose='LANDING_PAGES' and dns_status='VERIFIED'", "id", domainId, "ws", ws))
            errs.add(new FieldErr("domainId", "page.domain", "Connect and verify a landing-page domain first."));
        if (errs.isEmpty() && db.exists("select 1 from landing_pages where workspace_id=:ws and path=:p and coalesce(domain_id,0)=coalesce(:d,0)" + (exceptId != null ? " and id<>:id" : ""),
                "ws", ws, "p", path, "d", domainId, "id", exceptId == null ? 0L : exceptId))
            errs.add(new FieldErr("path", "page.path.exists", "This page address is already used."));
        if (r.seoTitle() != null && r.seoTitle().length() > 60) errs.add(new FieldErr("seoTitle", "page.seo.title", "Keep the title under 60 characters."));
        if (r.seoDescription() != null && r.seoDescription().length() > 160) errs.add(new FieldErr("seoDescription", "page.seo.desc", "Keep the description under 160 characters."));
        if (r.leadFormId() != null && !db.exists("select 1 from lead_forms where id=:id and workspace_id=:ws", "id", r.leadFormId(), "ws", ws))
            errs.add(new FieldErr("leadFormId", "page.form", "Choose one of your forms."));
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (r.blocks() == null || !r.blocks().isArray() || r.blocks().isEmpty() || r.blocks().size() > 30) {
            errs.add(new FieldErr("blocks", "page.blocks", "Add at least one block (up to 30)."));
        } else {
            for (JsonNode b : r.blocks()) {
                String t = b.path("type").asText("");
                if (!BLOCKS.contains(t)) {
                    errs.add(new FieldErr("blocks", "page.block.type", "Unknown block type."));
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", t);
                switch (t) {
                    case "hero" -> {
                        m.put("heading", Norm.plain(b.path("heading").asText("")));
                        m.put("subheading", Norm.plain(b.path("subheading").asText("")));
                        m.put("imageUrl", url(b.path("imageUrl").asText(null)));
                        m.put("buttonText", Norm.plain(b.path("buttonText").asText("")));
                        m.put("buttonUrl", url(b.path("buttonUrl").asText(null)));
                    }
                    case "text" -> m.put("html", Norm.basicHtml(b.path("html").asText("")));
                    case "image" -> {
                        m.put("url", url(b.path("url").asText(null)));
                        m.put("alt", Norm.plain(b.path("alt").asText("")));
                    }
                    case "offer" -> {
                        m.put("title", Norm.plain(b.path("title").asText("")));
                        m.put("price", Norm.plain(b.path("price").asText("")));
                        m.put("details", Norm.plain(b.path("details").asText("")));
                    }
                    case "countdown" -> {
                        LocalDateTime end;
                        try {
                            end = Time.parseUtc(b.path("endAt").asText());
                        } catch (Exception e) {
                            end = null;
                        }
                        if (end == null || end.isBefore(Time.now())) errs.add(new FieldErr("blocks", "page.countdown", "The countdown must end in the future."));
                        m.put("endAt", end == null ? null : end.toString() + "Z");
                        m.put("label", Norm.plain(b.path("label").asText("Offer ends in")));
                    }
                    case "form" -> {
                        if (r.leadFormId() == null) errs.add(new FieldErr("leadFormId", "page.form", "Choose one of your forms."));
                    }
                    case "whatsapp" -> {
                        String ph = Phones.e164(b.path("phone").asText(""));
                        if (ph == null) errs.add(new FieldErr("blocks", "vr.mobile.invalid", "Enter a valid 10-digit mobile number."));
                        m.put("phone", ph);
                        m.put("text", Norm.plain(b.path("text").asText("Hi")));
                        m.put("buttonText", Norm.plain(b.path("buttonText").asText("Chat on WhatsApp")));
                    }
                    case "appbadges" -> {
                        long appId = b.path("appId").asLong(0);
                        if (!db.exists("select 1 from apps where id=:id and workspace_id=:ws", "id", appId, "ws", ws)) errs.add(new FieldErr("blocks", "page.app", "Choose an app."));
                        m.put("appId", appId);
                    }
                    case "map" -> {
                        String u = b.path("url").asText("");
                        String h = UrlGuard.host(u);
                        if (!UrlGuard.isPublicHttps(u, 500) || h == null || !(h.contains("google.") || h.equals("maps.app.goo.gl") || h.equals("g.page")))
                            errs.add(new FieldErr("blocks", "vr.url.invalid", "Enter a valid https:// link."));
                        m.put("url", u);
                        m.put("label", Norm.plain(b.path("label").asText("Find us on the map")));
                    }
                    default -> {}
                }
                blocks.add(m);
            }
        }
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        return List.of(title, path, domainId == null ? "" : domainId, Json.write(blocks));
    }

    private String url(String u) {
        if (u == null || u.isBlank()) return null;
        if (!UrlGuard.isPublicHttps(u, 500) && !u.startsWith(settings.publicBaseUrl + "/files/")) throw ApiException.invalid("blocks", "vr.url.invalid", "Enter a valid https:// link.");
        return u;
    }

    public Map<String, Object> create(long ws, long userId, PageRequest r) {
        plans.requireFeature(ws, "forms"); // Growth and Agency
        List<Object> c = clean(ws, r, null);
        long id = db.insertInto("landing_pages", "workspace_id", ws, "domain_id", r.domainId(), "path", c.get(1), "title", c.get(0), "blocks_json", c.get(3),
                "lead_form_id", r.leadFormId(), "seo_title", Norm.text(r.seoTitle()), "seo_description", Norm.text(r.seoDescription()), "created_by", userId);
        audit.log("PAGE_CREATED", "landing_pages", id);
        return get(ws, id);
    }

    public Map<String, Object> update(long ws, long id, PageRequest r) {
        get(ws, id);
        List<Object> c = clean(ws, r, id);
        db.update("update landing_pages set domain_id=:d, path=:p, title=:t, blocks_json=:b, lead_form_id=:f, seo_title=:st, seo_description=:sd where id=:id",
                "d", r.domainId(), "p", c.get(1), "t", c.get(0), "b", c.get(3), "f", r.leadFormId(), "st", Norm.text(r.seoTitle()), "sd", Norm.text(r.seoDescription()), "id", id);
        return get(ws, id);
    }

    public Map<String, Object> publish(long ws, long id, boolean publish) {
        Row p = db.row("select * from landing_pages where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Page"));
        if (publish) {
            boolean cta = false;
            for (JsonNode b : Json.tree(p.s("blocks_json"))) if (CTA.contains(b.path("type").asText())) cta = true;
            if (!cta) throw ApiException.invalid("blocks", "page.cta", "Add a form, WhatsApp button or app badge before publishing.");
            db.update("update landing_pages set status='PUBLISHED', published_at=:now where id=:id", "now", Time.now(), "id", id);
        } else {
            db.update("update landing_pages set status='UNPUBLISHED' where id=:id", "id", id);
        }
        return get(ws, id);
    }

    public void delete(long ws, long id) {
        get(ws, id);
        db.update("delete from landing_pages where id=:id and workspace_id=:ws", "id", id, "ws", ws);
    }

    // --------------------------------------------------------------- public

    public Optional<String> render(Long ws, String host, String path) {
        Row p;
        if (ws != null) {
            p = db.row("select * from landing_pages where workspace_id=:ws and path=:p and domain_id is null and status='PUBLISHED'", "ws", ws, "p", path).orElse(null);
        } else {
            p = db.row("select p.* from landing_pages p join custom_domains d on d.id=p.domain_id where d.domain=:h and d.dns_status='VERIFIED' and p.path=:p and p.status='PUBLISHED'",
                    "h", host == null ? "" : host.toLowerCase(), "p", path).orElse(null);
        }
        if (p == null) return Optional.empty();
        return Optional.of(html(p));
    }

    private String html(Row p) {
        StringBuilder b = new StringBuilder();
        for (JsonNode blk : Json.tree(p.s("blocks_json"))) {
            switch (blk.path("type").asText()) {
                case "hero" -> {
                    b.append("<section class=\"hero\">");
                    if (blk.hasNonNull("imageUrl")) b.append("<img src=\"").append(Html.esc(blk.get("imageUrl").asText())).append("\" alt=\"\" loading=\"lazy\">");
                    b.append("<h1>").append(Html.esc(blk.path("heading").asText())).append("</h1><p>").append(Html.esc(blk.path("subheading").asText())).append("</p>");
                    if (blk.hasNonNull("buttonUrl")) b.append("<a class=\"btn\" href=\"").append(Html.esc(blk.get("buttonUrl").asText())).append("\">").append(Html.esc(blk.path("buttonText").asText("Learn more"))).append("</a>");
                    b.append("</section>");
                }
                case "text" -> b.append("<section class=\"txt\">").append(blk.path("html").asText()).append("</section>"); // already sanitised on save
                case "image" -> b.append("<section><img class=\"full\" src=\"").append(Html.esc(blk.path("url").asText())).append("\" alt=\"").append(Html.esc(blk.path("alt").asText())).append("\" loading=\"lazy\"></section>");
                case "offer" -> b.append("<section class=\"offer\"><h2>").append(Html.esc(blk.path("title").asText())).append("</h2><div class=\"price\">").append(Html.esc(blk.path("price").asText()))
                        .append("</div><p>").append(Html.esc(blk.path("details").asText())).append("</p></section>");
                case "countdown" -> b.append("<section class=\"cd\" data-end=\"").append(Html.esc(blk.path("endAt").asText())).append("\"><p>").append(Html.esc(blk.path("label").asText()))
                        .append("</p><div class=\"clock\">--</div></section>");
                case "form" -> {
                    String slug = p.lo("lead_form_id") == null ? null : db.scalar("select slug from lead_forms where id=:id and is_active=1", String.class, "id", p.l("lead_form_id"));
                    if (slug != null) b.append("<section><iframe title=\"Enquiry form\" src=\"/f/").append(Html.esc(slug)).append("\" class=\"frm\"></iframe></section>");
                }
                case "whatsapp" -> {
                    String digits = blk.path("phone").asText("").replace("+", "");
                    b.append("<section class=\"c\"><a class=\"btn wa\" href=\"https://wa.me/").append(Html.esc(digits)).append("?text=")
                            .append(java.net.URLEncoder.encode(blk.path("text").asText("Hi"), java.nio.charset.StandardCharsets.UTF_8)).append("\">")
                            .append(Html.esc(blk.path("buttonText").asText("Chat on WhatsApp"))).append("</a></section>");
                }
                case "appbadges" -> {
                    String code = db.scalar("select code from short_links where app_id=:id and type='APP' order by id limit 1", String.class, "id", blk.path("appId").asLong());
                    if (code != null) b.append("<section class=\"c\"><a class=\"btn\" href=\"/l/").append(Html.esc(code)).append("\">Get the app</a></section>");
                }
                case "map" -> b.append("<section class=\"c\"><a class=\"btn alt\" href=\"").append(Html.esc(blk.path("url").asText())).append("\" rel=\"noopener\">").append(Html.esc(blk.path("label").asText())).append("</a></section>");
                default -> {}
            }
        }
        String title = p.s("seo_title") != null ? p.s("seo_title") : p.s("title");
        String css = "body{margin:0;font:16px/1.55 system-ui,sans-serif;background:#f6f8fb;color:#0b1a33}main{max-width:560px;margin:0 auto}section{padding:18px 16px}" +
                ".hero{text-align:center;background:#0b1a33;color:#fff}.hero img{width:100%;border-radius:12px}.btn{display:inline-block;background:#12a388;color:#fff;padding:13px 22px;border-radius:10px;text-decoration:none;font-weight:700}" +
                ".btn.wa{background:#25d366}.btn.alt{background:#0b1a33}.c{text-align:center}.full{width:100%;border-radius:12px}.offer{text-align:center;background:#fff;border-radius:14px;margin:12px 16px}.price{font-size:2rem;font-weight:800;color:#12a388}" +
                ".cd{text-align:center}.clock{font-size:1.8rem;font-weight:800}.frm{width:100%;height:560px;border:0}@media(prefers-color-scheme:dark){body{background:#0b1220;color:#e8eef8}.offer{background:#111b2e}}";
        String js = "document.querySelectorAll('.cd').forEach(function(e){var end=new Date(e.dataset.end).getTime(),c=e.querySelector('.clock');function t(){var d=end-Date.now();" +
                "if(d<=0){c.textContent='Offer ended';return}var h=Math.floor(d/36e5),m=Math.floor(d%36e5/6e4),s=Math.floor(d%6e4/1e3);c.textContent=h+'h '+m+'m '+s+'s';setTimeout(t,1000)}t()});";
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>" + Html.esc(title) + "</title>" +
                (p.s("seo_description") != null ? "<meta name=\"description\" content=\"" + Html.esc(p.s("seo_description")) + "\">" : "") +
                "<style>" + css + "</style></head><body><main>" + b + "</main><script>" + js + "</script></body></html>";
    }
}
