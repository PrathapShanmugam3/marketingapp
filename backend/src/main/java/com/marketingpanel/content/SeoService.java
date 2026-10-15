package com.marketingpanel.content;

import com.marketingpanel.common.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

/** On-page SEO audit of any public https page, plus manual keyword rank tracking. */
@Service
public class SeoService {
    private static final int MAX_BYTES = 1_500_000;
    private final Db db;
    private final PlanService plans;
    private final RateLimiter limiter;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NEVER).build();

    public SeoService(Db db, PlanService plans, RateLimiter limiter) {
        this.db = db;
        this.plans = plans;
        this.limiter = limiter;
    }

    /** Fetches the page; every redirect target is re-checked so a public page cannot bounce the server to an internal address. */
    String fetch(String url, long[] timing) {
        String current = url;
        for (int hop = 0; hop < 4; hop++) {
            UrlGuard.assertSafeToFetch(current);
            try {
                long t0 = System.currentTimeMillis();
                HttpResponse<java.io.InputStream> res = http.send(HttpRequest.newBuilder(URI.create(current)).timeout(Duration.ofSeconds(8)).header("User-Agent", "MarketingPanelSEO/1.0").GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                timing[0] = System.currentTimeMillis() - t0;
                int sc = res.statusCode();
                if (sc >= 300 && sc < 400) {
                    String loc = res.headers().firstValue("Location").orElse(null);
                    if (loc == null) throw ApiException.badRequest("The page redirects without a destination.");
                    current = URI.create(current).resolve(loc).toString();
                    continue;
                }
                if (sc >= 400) throw ApiException.badRequest("The page returned HTTP " + sc + ".");
                String ct = res.headers().firstValue("Content-Type").orElse("");
                if (!ct.toLowerCase().contains("html")) throw ApiException.badRequest("That link is not a web page.");
                try (var in = res.body()) {
                    byte[] b = in.readNBytes(MAX_BYTES);
                    timing[1] = b.length;
                    return new String(b, java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                throw ApiException.badRequest("The page could not be opened. Check the link and try again.");
            }
        }
        throw ApiException.badRequest("The page redirects too many times.");
    }

    public Map<String, Object> audit(long ws, String url) {
        plans.requireFeature(ws, "content");
        String u = Norm.text(url);
        if (u == null || !UrlGuard.isPublicHttps(u, 500)) throw ApiException.invalid("url", "seo.url", "Enter a full https:// link.");
        limiter.check("seo:" + ws, 20, Duration.ofHours(1));
        long[] t = new long[2];
        String html = fetch(u, t);
        Document d = Jsoup.parse(html, u);
        List<Map<String, Object>> c = new ArrayList<>();
        String title = d.title();
        ContentService.check(c, "title", "Page title is 30–60 characters", title.length() >= 30 && title.length() <= 60 ? "PASS" : title.length() >= 10 && title.length() <= 70 ? "WARN" : "FAIL", title.isEmpty() ? "Missing" : title.length() + " characters: " + title, 15);
        String desc = d.select("meta[name=description]").attr("content");
        ContentService.check(c, "desc", "Meta description is 70–160 characters", desc.length() >= 70 && desc.length() <= 160 ? "PASS" : desc.length() >= 30 ? "WARN" : "FAIL", desc.isEmpty() ? "Missing" : desc.length() + " characters", 15);
        int h1 = d.select("h1").size();
        ContentService.check(c, "h1", "Exactly one main heading (h1)", h1 == 1 ? "PASS" : h1 == 0 ? "FAIL" : "WARN", h1 + " found", 10);
        ContentService.check(c, "h2", "Has sub-headings", d.select("h2,h3").isEmpty() ? "WARN" : "PASS", d.select("h2,h3").size() + " found", 5);
        ContentService.check(c, "viewport", "Mobile friendly (viewport tag)", d.select("meta[name=viewport]").isEmpty() ? "FAIL" : "PASS", null, 10);
        ContentService.check(c, "lang", "Page language is declared", d.select("html[lang]").isEmpty() ? "WARN" : "PASS", d.select("html").attr("lang"), 5);
        ContentService.check(c, "canonical", "Canonical link present", d.select("link[rel=canonical]").isEmpty() ? "WARN" : "PASS", d.select("link[rel=canonical]").attr("href"), 5);
        ContentService.check(c, "og", "Social sharing tags (Open Graph)", d.select("meta[property=og:title]").isEmpty() ? "WARN" : "PASS", null, 5);
        boolean noindex = d.select("meta[name=robots]").attr("content").toLowerCase().contains("noindex");
        ContentService.check(c, "noindex", "Page can be indexed by search engines", noindex ? "FAIL" : "PASS", noindex ? "A noindex tag is present" : null, 10);
        long noAlt = d.select("img").stream().filter(i -> i.attr("alt").isBlank()).count();
        ContentService.check(c, "alt", "Images have descriptions (alt text)", noAlt == 0 ? "PASS" : noAlt <= 2 ? "WARN" : "FAIL", noAlt + " of " + d.select("img").size() + " images without alt text", 10);
        String text = d.body() == null ? "" : d.body().text();
        int words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
        ContentService.check(c, "words", "Enough text (300+ words)", words >= 300 ? "PASS" : words >= 100 ? "WARN" : "FAIL", words + " words", 5);
        ContentService.check(c, "speed", "Server responds in under 1.5 s", t[0] < 1500 ? "PASS" : t[0] < 3000 ? "WARN" : "FAIL", t[0] + " ms", 5);
        ContentService.check(c, "size", "HTML under 500 KB", t[1] < 500_000 ? "PASS" : "WARN", (t[1] / 1024) + " KB", 5);
        int score = ContentService.score(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", u);
        out.put("score", score);
        out.put("checks", c);
        long id = db.insertInto("seo_audits", "workspace_id", ws, "url", u, "score", score, "result_json", Json.write(c));
        out.put("id", id);
        return out;
    }

    public List<Map<String, Object>> history(long ws) {
        return db.list("select id, url, score, created_at from seo_audits where workspace_id=:ws order by id desc limit 30", "ws", ws);
    }

    public Map<String, Object> auditDetail(long ws, long id) {
        Row r = db.row("select * from seo_audits where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Audit"));
        Map<String, Object> m = new LinkedHashMap<>(r.api());
        m.put("checks", Json.list(r.s("result_json")));
        m.remove("resultJson");
        return m;
    }

    // ------------------------------------------------------------------ keyword rank tracking (entered by hand or imported from Search Console)
    public List<Map<String, Object>> keywords(long ws) {
        List<Map<String, Object>> rows = db.list("select k.id, k.keyword, k.target_url, " +
                "(select position from seo_rankings r where r.keyword_id=k.id order by checked_on desc limit 1) as position, " +
                "(select position from seo_rankings r where r.keyword_id=k.id order by checked_on desc limit 1 offset 1) as previous, " +
                "(select checked_on from seo_rankings r where r.keyword_id=k.id order by checked_on desc limit 1) as checked_on from seo_keywords k where k.workspace_id=:ws order by k.keyword", "ws", ws);
        for (Map<String, Object> m : rows) {
            m.put("history", db.list("select checked_on, position from seo_rankings where keyword_id=:k order by checked_on desc limit 30", "k", m.get("id")));
        }
        return rows;
    }

    public record KeywordRequest(String keyword, String targetUrl) {}

    public Map<String, Object> addKeyword(long ws, KeywordRequest r) {
        plans.requireFeature(ws, "content");
        String k = Norm.plain(r.keyword());
        if (k == null || k.length() < 2 || k.length() > 100) throw ApiException.invalid("keyword", "seo.kw", "Keyword must be 2 to 100 characters.");
        String url = Norm.text(r.targetUrl());
        if (url != null && !UrlGuard.isPublicHttps(url, 500)) throw ApiException.invalid("targetUrl", "vr.url.invalid", "Enter a valid https:// link.");
        if (db.count("select count(*) from seo_keywords where workspace_id=:ws", "ws", ws) >= 100) throw ApiException.planLimit("You can track up to 100 keywords.");
        try {
            long id = db.insertInto("seo_keywords", "workspace_id", ws, "keyword", k.toLowerCase(), "target_url", url);
            return Map.of("id", id);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.duplicate("keyword", "seo.kw.exists", "You already track this keyword.");
        }
    }

    public record RankRequest(Integer position, String day) {}

    public Map<String, Object> logRank(long ws, long keywordId, RankRequest r) {
        db.row("select id from seo_keywords where id=:id and workspace_id=:ws", "id", keywordId, "ws", ws).orElseThrow(() -> ApiException.notFound("Keyword"));
        if (r.position() == null || r.position() < 1 || r.position() > 1000) throw ApiException.invalid("position", "seo.pos", "Enter a position from 1 to 1000.");
        LocalDate day = r.day() == null || r.day().isBlank() ? Time.todayIst() : LocalDate.parse(r.day());
        if (day.isAfter(Time.todayIst())) throw ApiException.invalid("day", "seo.day", "The date cannot be in the future.");
        db.update("insert into seo_rankings (keyword_id, checked_on, position) values (:k,:d,:p) on duplicate key update position=:p", "k", keywordId, "d", day, "p", r.position());
        return Map.of("ok", true);
    }

    public void deleteKeyword(long ws, long id) {
        if (db.update("delete from seo_keywords where id=:id and workspace_id=:ws", "id", id, "ws", ws) == 0) throw ApiException.notFound("Keyword");
    }
}
