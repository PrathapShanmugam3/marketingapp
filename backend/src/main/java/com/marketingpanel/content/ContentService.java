package com.marketingpanel.content;

import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** Content calendar (ideas, drafts, scheduled posts) with a public blog, sitemap and an on-page SEO checklist. */
@Service
public class ContentService {
    private final Db db;
    private final PlanService plans;
    private final AuditService audit;
    private final Settings settings;

    public ContentService(Db db, PlanService plans, AuditService audit, Settings settings) {
        this.db = db;
        this.plans = plans;
        this.audit = audit;
        this.settings = settings;
    }

    public record Request(String type, String title, String slug, String bodyHtml, String coverUrl, String seoTitle, String seoDescription, String keywords, String status, String publishAt) {}

    public List<Map<String, Object>> list(long ws, String month, String status, String type) {
        StringBuilder sb = new StringBuilder("select id, type, title, slug, status, publish_at, published_at, view_count, seo_title, created_at, updated_at from content_items where workspace_id=:ws");
        List<Object> kv = new ArrayList<>(List.of("ws", ws));
        if (month != null && month.matches("^\\d{4}-\\d{2}$")) {
            LocalDate d = LocalDate.parse(month + "-01");
            sb.append(" and coalesce(publish_at, created_at) >= :f and coalesce(publish_at, created_at) < :t");
            kv.addAll(List.of("f", Time.startOfIstDayUtc(d), "t", Time.startOfIstDayUtc(d.plusMonths(1))));
        }
        if (status != null && !status.isBlank()) {
            sb.append(" and status=:s");
            kv.addAll(List.of("s", status));
        }
        if (type != null && !type.isBlank()) {
            sb.append(" and type=:ty");
            kv.addAll(List.of("ty", type));
        }
        return db.list(sb + " order by coalesce(publish_at, created_at) desc limit 500", kv.toArray());
    }

    public Map<String, Object> get(long ws, long id) {
        return db.one("select * from content_items where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Content"));
    }

    static String slugify(String s) {
        String x = s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return x.length() > 80 ? x.substring(0, 80).replaceAll("-$", "") : x;
    }

    private Map<String, Object> clean(long ws, Long exceptId, Request r) {
        List<FieldErr> errs = new ArrayList<>();
        String type = r.type() == null ? "BLOG" : r.type();
        if (!List.of("BLOG", "IDEA", "SOCIAL", "EMAIL", "VIDEO").contains(type)) errs.add(new FieldErr("type", "content.type", "Choose a content type."));
        String title = Norm.plain(r.title());
        if (title == null || title.length() < 3 || title.length() > 150) errs.add(new FieldErr("title", "content.title", "Title must be 3 to 150 characters."));
        String status = r.status() == null ? "DRAFT" : r.status();
        if (!List.of("IDEA", "DRAFT", "SCHEDULED", "PUBLISHED").contains(status)) errs.add(new FieldErr("status", "content.status", "Choose a status."));
        LocalDateTime at = null;
        if (r.publishAt() != null && !r.publishAt().isBlank()) {
            try {
                at = Time.parseUtc(r.publishAt());
            } catch (Exception e) {
                errs.add(new FieldErr("publishAt", "content.when", "Choose a valid date and time."));
            }
        }
        if ("SCHEDULED".equals(status) && (at == null || at.isBefore(Time.now().minusMinutes(1)))) errs.add(new FieldErr("publishAt", "content.when", "Choose a future date and time."));
        String slug = r.slug() == null || r.slug().isBlank() ? (title == null ? null : slugify(title)) : slugify(r.slug());
        if ("BLOG".equals(type)) {
            if (slug == null || slug.length() < 3) errs.add(new FieldErr("slug", "content.slug", "Use at least 3 letters or digits in the link."));
            else if (db.exists("select 1 from content_items where workspace_id=:ws and slug=:s" + (exceptId != null ? " and id<>:id" : ""), "ws", ws, "s", slug, "id", exceptId == null ? 0L : exceptId))
                errs.add(new FieldErr("slug", "content.slug.exists", "This link is already used by another post."));
        } else {
            slug = null;
        }
        String body = r.bodyHtml() == null ? null : Norm.basicHtml(r.bodyHtml());
        if (("PUBLISHED".equals(status) || "SCHEDULED".equals(status)) && "BLOG".equals(type) && (body == null || Jsoup.parse(body).text().length() < 30))
            errs.add(new FieldErr("bodyHtml", "content.body", "Write the post before publishing (at least 30 characters)."));
        String cover = Norm.text(r.coverUrl());
        if (cover != null && !(cover.startsWith("/files/") || UrlGuard.isPublicHttps(cover, 500))) errs.add(new FieldErr("coverUrl", "vr.url.invalid", "Upload an image or enter a valid https:// link."));
        String st = Norm.plain(r.seoTitle());
        String sd = Norm.plain(r.seoDescription());
        if (st != null && st.length() > 70) errs.add(new FieldErr("seoTitle", "vr.size", "Keep the search title within 70 characters."));
        if (sd != null && sd.length() > 160) errs.add(new FieldErr("seoDescription", "vr.size", "Keep the search description within 160 characters."));
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("title", title);
        m.put("slug", slug);
        m.put("body_html", body);
        m.put("cover_url", cover);
        m.put("seo_title", st);
        m.put("seo_description", sd);
        m.put("keywords", Norm.plain(r.keywords()));
        m.put("status", status);
        m.put("publish_at", at);
        if ("PUBLISHED".equals(status)) m.put("published_at", Time.now());
        return m;
    }

    public Map<String, Object> create(long ws, long userId, Request r) {
        plans.requireFeature(ws, "content");
        Map<String, Object> m = clean(ws, null, r);
        Object[] kv = new Object[m.size() * 2 + 4];
        kv[0] = "workspace_id";
        kv[1] = ws;
        kv[2] = "author_id";
        kv[3] = userId;
        int i = 4;
        for (var e : m.entrySet()) {
            kv[i++] = e.getKey();
            kv[i++] = e.getValue();
        }
        long id = db.insertInto("content_items", kv);
        audit.log("CONTENT_CREATED", "content_items", id);
        return get(ws, id);
    }

    public Map<String, Object> update(long ws, long id, Request r) {
        Map<String, Object> old = get(ws, id);
        Map<String, Object> m = clean(ws, id, r);
        if ("PUBLISHED".equals(old.get("status"))) m.remove("published_at"); // keep the original publish time
        db.updateTable("content_items", "id", id, m, "workspace_id", ws);
        return get(ws, id);
    }

    public void delete(long ws, long id) {
        if (db.update("delete from content_items where id=:id and workspace_id=:ws", "id", id, "ws", ws) == 0) throw ApiException.notFound("Content");
    }

    /** Scheduler: scheduled posts become public when their time arrives. */
    public void publishDue() {
        db.update("update content_items set status='PUBLISHED', published_at=publish_at where status='SCHEDULED' and publish_at <= :now", "now", Time.now());
    }

    // ------------------------------------------------------------------ SEO checklist for a post
    public Map<String, Object> seoCheck(long ws, long id) {
        Row c = db.row("select * from content_items where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Content"));
        Document doc = Jsoup.parse(c.s("body_html") == null ? "" : c.s("body_html"));
        String text = doc.text();
        int words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
        String kw = c.s("keywords") == null ? "" : c.s("keywords").split(",")[0].trim().toLowerCase();
        String title = (c.s("seo_title") != null ? c.s("seo_title") : c.s("title"));
        String desc = c.s("seo_description") == null ? "" : c.s("seo_description");
        List<Map<String, Object>> checks = new ArrayList<>();
        check(checks, "title", "Search title is 30–60 characters", title.length() >= 30 && title.length() <= 60 ? "PASS" : title.length() >= 15 && title.length() <= 70 ? "WARN" : "FAIL", title.length() + " characters", 15);
        check(checks, "desc", "Search description is 70–160 characters", desc.length() >= 70 && desc.length() <= 160 ? "PASS" : desc.length() >= 30 ? "WARN" : "FAIL", desc.length() + " characters", 15);
        check(checks, "words", "At least 300 words", words >= 300 ? "PASS" : words >= 150 ? "WARN" : "FAIL", words + " words", 15);
        check(checks, "headings", "Has sub-headings (h2/h3)", doc.select("h2,h3").isEmpty() ? "WARN" : "PASS", doc.select("h2,h3").size() + " found", 10);
        long noAlt = doc.select("img").stream().filter(i -> i.attr("alt").isBlank()).count();
        check(checks, "alt", "Images have descriptions (alt text)", noAlt == 0 ? "PASS" : "WARN", noAlt + " without alt text", 10);
        check(checks, "links", "Links to another page", doc.select("a[href]").isEmpty() ? "WARN" : "PASS", doc.select("a[href]").size() + " links", 5);
        if (kw.isEmpty()) {
            check(checks, "keyword", "A focus keyword is set", "FAIL", "Add keywords in the SEO box", 10);
        } else {
            check(checks, "kwTitle", "Focus keyword \"" + kw + "\" is in the title", title.toLowerCase().contains(kw) ? "PASS" : "FAIL", null, 10);
            check(checks, "kwDesc", "Keyword is in the search description", desc.toLowerCase().contains(kw) ? "PASS" : "WARN", null, 5);
            String first = text.length() > 600 ? text.substring(0, 600).toLowerCase() : text.toLowerCase();
            check(checks, "kwIntro", "Keyword appears in the opening paragraph", first.contains(kw) ? "PASS" : "WARN", null, 5);
            long occ = words == 0 ? 0 : (text.toLowerCase().split(java.util.regex.Pattern.quote(kw), -1).length - 1);
            double density = words == 0 ? 0 : occ * 100.0 / words;
            check(checks, "kwDensity", "Keyword density 0.5–3%", density >= 0.5 && density <= 3 ? "PASS" : "WARN", String.format("%.1f%%", density), 5);
        }
        check(checks, "cover", "Cover image added", c.s("cover_url") != null ? "PASS" : "WARN", null, 5);
        return Map.of("score", score(checks), "checks", checks);
    }

    static void check(List<Map<String, Object>> out, String key, String label, String status, String detail, int weight) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("status", status);
        m.put("detail", detail);
        m.put("weight", weight);
        out.add(m);
    }

    static int score(List<Map<String, Object>> checks) {
        int total = 0;
        double got = 0;
        for (Map<String, Object> c : checks) {
            int w = (Integer) c.get("weight");
            total += w;
            got += switch ((String) c.get("status")) {
                case "PASS" -> w;
                case "WARN" -> w * 0.5;
                default -> 0;
            };
        }
        return total == 0 ? 0 : (int) Math.round(got * 100 / total);
    }

    // ------------------------------------------------------------------ public blog
    private Row workspace(long ws) {
        return db.row("select id, name, city, brand_color from workspaces where id=:id and status in ('TRIAL','ACTIVE') and deleted_at is null", "id", ws).orElseThrow(() -> ApiException.notFound("Blog"));
    }

    private static final String CSS = "<style>body{font-family:system-ui,sans-serif;margin:0;color:#14213d;background:#fff}.wrap{max-width:720px;margin:0 auto;padding:20px}a{color:#128C7E}img{max-width:100%;height:auto;border-radius:10px}" +
            ".muted{color:#667}.card{border:1px solid #e3e6ec;border-radius:12px;padding:14px;margin:12px 0}h1{line-height:1.2}</style>";

    private String doc(String title, String desc, String canonical, String cover, String bodyHtml) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>" + Html.esc(title) + "</title>"
                + (desc == null || desc.isBlank() ? "" : "<meta name=\"description\" content=\"" + Html.esc(desc) + "\">")
                + "<link rel=\"canonical\" href=\"" + Html.esc(canonical) + "\"><meta property=\"og:title\" content=\"" + Html.esc(title) + "\">"
                + (desc == null ? "" : "<meta property=\"og:description\" content=\"" + Html.esc(desc) + "\">")
                + (cover == null ? "" : "<meta property=\"og:image\" content=\"" + Html.esc(cover.startsWith("/") ? settings.publicBaseUrl + cover : cover) + "\">")
                + CSS + "</head><body><div class=\"wrap\">" + bodyHtml + "</div></body></html>";
    }

    public String blogIndex(long ws) {
        Row w = workspace(ws);
        StringBuilder b = new StringBuilder("<h1>" + Html.esc(w.s("name")) + " blog</h1>");
        List<Row> posts = db.rows("select title, slug, seo_description, cover_url, published_at from content_items where workspace_id=:ws and type='BLOG' and status='PUBLISHED' order by published_at desc limit 100", "ws", ws);
        if (posts.isEmpty()) b.append("<p class=\"muted\">No posts yet.</p>");
        for (Row p : posts)
            b.append("<div class=\"card\"><h2><a href=\"/b/").append(ws).append('/').append(Html.esc(p.s("slug"))).append("\">").append(Html.esc(p.s("title"))).append("</a></h2>")
                    .append(p.s("seo_description") == null ? "" : "<p>" + Html.esc(p.s("seo_description")) + "</p>").append("</div>");
        return doc(w.s("name") + " blog", "Articles from " + w.s("name"), settings.publicBaseUrl + "/b/" + ws, null, b.toString());
    }

    public String article(long ws, String slug) {
        Row w = workspace(ws);
        Row c = db.row("select * from content_items where workspace_id=:ws and slug=:s and type='BLOG' and status='PUBLISHED'", "ws", ws, "s", slug).orElseThrow(() -> ApiException.notFound("Post"));
        db.update("update content_items set view_count=view_count+1 where id=:id", "id", c.l("id"));
        String body = "<p><a href=\"/b/" + ws + "\">← " + Html.esc(w.s("name")) + "</a></p>" + (c.s("cover_url") == null ? "" : "<img alt=\"\" src=\"" + Html.esc(c.s("cover_url")) + "\">")
                + "<h1>" + Html.esc(c.s("title")) + "</h1><p class=\"muted\">" + Html.esc(c.t("published_at") == null ? "" : Time.fmt(c.t("published_at"))) + "</p>" + (c.s("body_html") == null ? "" : c.s("body_html"));
        return doc(c.s("seo_title") != null ? c.s("seo_title") : c.s("title"), c.s("seo_description"), settings.publicBaseUrl + "/b/" + ws + "/" + slug, c.s("cover_url"), body);
    }

    public String sitemap(long ws) {
        workspace(ws);
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        b.append("<url><loc>").append(Html.esc(settings.publicBaseUrl + "/b/" + ws)).append("</loc></url>");
        for (Row p : db.rows("select slug, updated_at from content_items where workspace_id=:ws and type='BLOG' and status='PUBLISHED' order by id desc limit 5000", "ws", ws))
            b.append("<url><loc>").append(Html.esc(settings.publicBaseUrl + "/b/" + ws + "/" + p.s("slug"))).append("</loc><lastmod>").append(p.t("updated_at").toLocalDate()).append("</lastmod></url>");
        return b.append("</urlset>").toString();
    }
}
