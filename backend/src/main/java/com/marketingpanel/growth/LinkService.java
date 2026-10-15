package com.marketingpanel.growth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** P28 Smart links and QR codes. Redirects read one cached row and record the click asynchronously (< 100 ms). */
@Service
public class LinkService {
    static final Set<String> RESERVED = Set.of("api", "admin", "login", "signup", "static", "assets", "files", "webhooks", "internal", "health", "www");
    static final Set<String> SHORTENERS = Set.of("bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "rb.gy", "cutt.ly", "shorturl.at", "buff.ly", "rebrand.ly");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_-]{3,30}$");
    private static final Pattern UTM = Pattern.compile("^[a-z0-9_-]{1,100}$");
    private static final Pattern BOT = Pattern.compile("(?i)(bot|crawler|spider|facebookexternalhit|whatsapp|telegram|slackbot|preview|curl|wget|python-requests|headless|twitterbot|linkedinbot|skypeuripreview)");

    private final Db db;
    private final Settings settings;
    private final PlanService plans;
    private final ApplicationEventPublisher events;
    private final AuditService audit;
    private final ExecutorService clicks = Executors.newVirtualThreadPerTaskExecutor();
    private final Cache<String, Optional<Row>> cache = Caffeine.newBuilder().expireAfterWrite(60, TimeUnit.SECONDS).maximumSize(50_000).build();

    public LinkService(Db db, Settings settings, PlanService plans, ApplicationEventPublisher events, AuditService audit) {
        this.db = db;
        this.settings = settings;
        this.plans = plans;
        this.events = events;
        this.audit = audit;
    }

    public record LinkRequest(String type, String destinationUrl, Long appId, Long domainId, String code, String utmSource, String utmMedium, String utmCampaign, String expiresAt) {}

    public List<Map<String, Object>> list(long ws) {
        List<Map<String, Object>> rows = db.list("select l.*, a.name as app_name, d.domain from short_links l left join apps a on a.id=l.app_id left join custom_domains d on d.id=l.domain_id " +
                "where l.workspace_id=:ws and l.campaign_id is null order by l.id desc limit 300", "ws", ws);
        for (Map<String, Object> m : rows) m.put("shortUrl", url(m.get("domain") == null ? null : (String) m.get("domain"), (String) m.get("code")));
        return rows;
    }

    public String url(String domain, String code) {
        return (domain == null ? settings.publicBaseUrl : "https://" + domain) + "/l/" + code;
    }

    public Map<String, Object> create(long ws, Long userId, LinkRequest r) {
        List<FieldErr> errs = new ArrayList<>();
        String type = r.type() == null ? "" : r.type();
        if (!List.of("URL", "APP").contains(type)) throw ApiException.invalid("type", "link.type", "Choose a link type.");
        String dest = Norm.text(r.destinationUrl());
        if ("URL".equals(type)) {
            if (dest == null || !UrlGuard.isPublicHttps(dest, 2000)) errs.add(new FieldErr("destinationUrl", "link.url", "Enter a valid https:// link."));
            else if (SHORTENERS.contains(String.valueOf(UrlGuard.host(dest)).toLowerCase())) errs.add(new FieldErr("destinationUrl", "link.shortener", "This link cannot be shortened."));
        } else if (r.appId() == null || !db.exists("select 1 from apps where id=:id and workspace_id=:ws", "id", r.appId(), "ws", ws)) {
            errs.add(new FieldErr("appId", "link.app", "Choose an app."));
        }
        Long domainId = r.domainId();
        if (domainId != null && !db.exists("select 1 from custom_domains where id=:id and workspace_id=:ws and purpose='LINKS' and dns_status='VERIFIED'", "id", domainId, "ws", ws))
            errs.add(new FieldErr("domainId", "link.domain", "Choose the platform domain or a verified links domain."));
        for (String[] u : new String[][]{{"utmSource", r.utmSource()}, {"utmMedium", r.utmMedium()}, {"utmCampaign", r.utmCampaign()}})
            if (u[1] != null && !u[1].isBlank() && !UTM.matcher(u[1]).matches()) errs.add(new FieldErr(u[0], "link.utm", "Use lowercase letters, numbers, - and _."));
        LocalDateTime exp = null;
        if (r.expiresAt() != null && !r.expiresAt().isBlank()) {
            exp = Time.parseUtc(r.expiresAt());
            if (exp.isBefore(Time.now().plusMinutes(10)) || exp.isAfter(Time.now().plusDays(366))) errs.add(new FieldErr("expiresAt", "vr.future.invalid", "Choose a future date."));
        }
        String code = Norm.text(r.code());
        if (code != null) {
            if (!CODE.matcher(code).matches()) errs.add(new FieldErr("code", "link.code", "Use 3 to 30 letters, numbers, - or _."));
            else if (RESERVED.contains(code.toLowerCase()) || db.exists("select 1 from short_links where code=:c", "c", code)) throw ApiException.duplicate("code", "link.code.taken", "This code is taken. Try another.");
        }
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        if (code == null) {
            do {
                code = Ids.randomCode(7);
            } while (RESERVED.contains(code.toLowerCase()) || db.exists("select 1 from short_links where code=:c", "c", code));
        }
        long id = db.insertInto("short_links", "workspace_id", ws, "domain_id", domainId, "code", code, "type", type, "app_id", "APP".equals(type) ? r.appId() : null,
                "destination_url", "URL".equals(type) ? dest : null, "utm_source", blank(r.utmSource()), "utm_medium", blank(r.utmMedium()),
                "utm_campaign", blank(r.utmCampaign()), "expires_at", exp, "created_by", userId);
        audit.log("LINK_CREATED", "short_links", id);
        return db.one("select l.*, d.domain from short_links l left join custom_domains d on d.id=l.domain_id where l.id=:id", "id", id).map(m -> {
            m.put("shortUrl", url((String) m.get("domain"), (String) m.get("code")));
            return m;
        }).orElseThrow();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public Map<String, Object> toggle(long ws, long id, boolean active) {
        Row l = load(ws, id);
        db.update("update short_links set is_active=:a where id=:id", "a", active, "id", id);
        cache.invalidate(l.s("code"));
        return db.one("select * from short_links where id=:id", "id", id).orElseThrow();
    }

    public void delete(long ws, long id) {
        Row l = load(ws, id);
        db.update("delete from short_links where id=:id", "id", id);
        cache.invalidate(l.s("code"));
    }

    private Row load(long ws, long id) {
        return db.row("select * from short_links where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Link"));
    }

    public void evictApp(long appId) {
        cache.invalidateAll();
    }

    public Map<String, Object> stats(long ws, long id) {
        Row l = load(ws, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clicks", l.l("click_count"));
        out.put("uniqueClicks", l.l("unique_click_count"));
        out.put("byDay", db.list("select date(convert_tz(clicked_at,'+00:00','+05:30')) as day, count(*) as clicks from link_clicks where short_link_id=:id and is_bot=0 and clicked_at > :t group by day order by day",
                "id", id, "t", Time.now().minusDays(30)));
        out.put("byPlatform", db.list("select platform, count(*) as clicks from link_clicks where short_link_id=:id and is_bot=0 group by platform", "id", id));
        out.put("link", db.one("select l.*, d.domain from short_links l left join custom_domains d on d.id=l.domain_id where l.id=:id", "id", id).orElseThrow());
        return out;
    }

    public byte[] qr(long ws, long id) {
        Row l = load(ws, id);
        String domain = l.lo("domain_id") == null ? null : db.scalar("select domain from custom_domains where id=:id", String.class, "id", l.l("domain_id"));
        return com.marketingpanel.review.ReviewService.qrPng(url(domain, l.s("code")), 512);
    }

    // ------------------------------------------------------------- redirect

    public record Redirect(int status, String location) {}

    private final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();

    /** Blocks until queued click writes finished (tests and graceful shutdown). */
    public void awaitClicks() throws InterruptedException {
        for (int i = 0; i < 200 && pending.get() > 0; i++) Thread.sleep(10);
    }

    static String platform(String ua) {
        if (ua == null) return "OTHER";
        if (ua.contains("Android")) return "ANDROID";
        if (ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod")) return "IOS";
        if (ua.contains("Windows") || ua.contains("Macintosh") || ua.contains("X11") || ua.contains("CrOS") || ua.contains("Linux")) return "DESKTOP";
        return "OTHER";
    }

    /** Resolves a public short code to 302 (location), 404 (unknown) or 410 (disabled / expired). */
    public Redirect resolve(String code, String ua, String referrer, String ip, String contactParam) {
        Row l = cache.get(code, c -> db.row("select l.*, a.android_package, a.play_store_url, a.ios_app_id, a.app_store_url, a.web_fallback_url from short_links l " +
                "left join apps a on a.id=l.app_id where l.code=:c", "c", c)).orElse(null);
        if (l == null) return new Redirect(404, null);
        boolean expired = l.t("expires_at") != null && l.t("expires_at").isBefore(Time.now());
        if (!l.b("is_active") || expired) return new Redirect(410, null);
        String platform = platform(ua);
        boolean bot = ua == null || BOT.matcher(ua).find();
        String target;
        String to;
        if ("APP".equals(l.s("type"))) {
            if ("ANDROID".equals(platform) && l.s("play_store_url") != null) {
                target = l.s("play_store_url");
                String ref = referrerParam(l);
                if (ref != null) target += (target.contains("?") ? "&" : "?") + "referrer=" + ref;
                to = "PLAY_STORE";
            } else if ("IOS".equals(platform) && l.s("app_store_url") != null) {
                target = l.s("app_store_url");
                if (l.s("utm_campaign") != null) target += (target.contains("?") ? "&" : "?") + "ct=" + URLEncoder.encode(l.s("utm_campaign"), StandardCharsets.UTF_8);
                to = "APP_STORE";
            } else {
                target = l.s("web_fallback_url");
                to = "FALLBACK";
            }
        } else {
            target = withUtm(l.s("destination_url"), l);
            to = "WEB";
        }
        if (target == null) return new Redirect(404, null);
        if (!bot) {
            pending.incrementAndGet();
            clicks.submit(() -> {
                try {
                    record(l, platform, to, ip, referrer, contactParam);
                } catch (Exception ignored) {
                    // a lost click must never break the redirect
                } finally {
                    pending.decrementAndGet();
                }
            });
        }
        return new Redirect(302, target);
    }

    private static String referrerParam(Row l) {
        List<String> parts = new ArrayList<>();
        if (l.s("utm_source") != null) parts.add("utm_source=" + l.s("utm_source"));
        if (l.s("utm_medium") != null) parts.add("utm_medium=" + l.s("utm_medium"));
        if (l.s("utm_campaign") != null) parts.add("utm_campaign=" + l.s("utm_campaign"));
        return parts.isEmpty() ? null : URLEncoder.encode(String.join("&", parts), StandardCharsets.UTF_8);
    }

    private static String withUtm(String url, Row l) {
        if (url == null) return null;
        StringBuilder sb = new StringBuilder(url);
        String[][] utm = {{"utm_source", l.s("utm_source")}, {"utm_medium", l.s("utm_medium")}, {"utm_campaign", l.s("utm_campaign")}};
        for (String[] u : utm) {
            if (u[1] != null && !url.contains(u[0] + "=")) sb.append(sb.indexOf("?") >= 0 ? "&" : "?").append(u[0]).append('=').append(URLEncoder.encode(u[1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private void record(Row l, String platform, String to, String ip, String referrer, String contactParam) {
        String ipHash = ClientInfo.ipHash(ip == null ? "" : ip, settings.ipHashSalt);
        boolean unique = !db.exists("select 1 from link_clicks where short_link_id=:l and ip_hash=:h and is_bot=0 and clicked_at > :t limit 1", "l", l.l("id"), "h", ipHash, "t", Time.now().minusHours(24));
        Long contactId = null;
        if (contactParam != null && contactParam.matches("^\\d{1,18}$")) {
            long c = Long.parseLong(contactParam);
            if (db.exists("select 1 from contacts where id=:c and workspace_id=:ws", "c", c, "ws", l.l("workspace_id"))) contactId = c;
        }
        db.insertInto("link_clicks", "short_link_id", l.l("id"), "contact_id", contactId, "platform", platform, "redirected_to", to,
                "referrer", referrer == null ? null : (referrer.length() > 255 ? referrer.substring(0, 255) : referrer), "ip_hash", ipHash);
        db.update("update short_links set click_count=click_count+1, unique_click_count=unique_click_count+:u where id=:id", "u", unique ? 1 : 0, "id", l.l("id"));
        if (contactId != null) events.publishEvent(new Events.LinkClicked(l.l("workspace_id"), contactId, l.l("id")));
    }
}
