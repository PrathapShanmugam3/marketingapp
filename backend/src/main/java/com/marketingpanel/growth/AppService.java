package com.marketingpanel.growth;

import com.marketingpanel.common.*;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** P27 App promotion: app profiles with Play Store / App Store ids and a device-aware smart link. */
@Service
public class AppService {
    static final Pattern PACKAGE = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$");
    private final Db db;
    private final PlanService plans;
    private final LinkService links;
    private final AuditService audit;

    public AppService(Db db, PlanService plans, LinkService links, AuditService audit) {
        this.db = db;
        this.plans = plans;
        this.links = links;
        this.audit = audit;
    }

    public record AppRequest(String name, String androidPackage, String playStoreUrl, String iosAppId, String appStoreUrl, String webFallbackUrl, String iconUrl) {}

    public List<Map<String, Object>> list(long ws) {
        return db.list("select a.*, (select code from short_links l where l.app_id=a.id and l.type='APP' order by l.id limit 1) as smart_code, " +
                "(select coalesce(sum(click_count),0) from short_links l where l.app_id=a.id) as clicks from apps a where a.workspace_id=:ws order by a.id desc", "ws", ws);
    }

    private Map<String, Object> clean(AppRequest r) {
        List<FieldErr> errs = new ArrayList<>();
        String name = Norm.plain(Norm.name(r.name()));
        if (name == null || name.length() < 2 || name.length() > 100) errs.add(new FieldErr("name", "app.name", "Enter the app name."));
        String pkg = Norm.text(r.androidPackage());
        String play = Norm.text(r.playStoreUrl());
        String iosId = Norm.text(r.iosAppId());
        String store = Norm.text(r.appStoreUrl());
        if (pkg != null && (pkg.length() > 150 || !PACKAGE.matcher(pkg).matches())) errs.add(new FieldErr("androidPackage", "app.package", "Enter a package name like in.yourshop.app."));
        if (play != null) {
            if (pkg == null) errs.add(new FieldErr("androidPackage", "app.package", "Enter a package name like in.yourshop.app."));
            String host = UrlGuard.host(play);
            String id = queryParam(play, "id");
            if (!UrlGuard.isPublicHttps(play, 500) || !"play.google.com".equals(host) || id == null || !id.equals(pkg))
                errs.add(new FieldErr("playStoreUrl", "app.play", "The Play Store link does not match the package name."));
        }
        if (store != null && iosId == null) {
            Matcher m = Pattern.compile("id(\\d{6,12})").matcher(store);
            if (UrlGuard.isPublicHttps(store, 500) && "apps.apple.com".equals(UrlGuard.host(store)) && m.find()) iosId = m.group(1);
        }
        if (iosId != null && !iosId.matches("^\\d{6,12}$")) errs.add(new FieldErr("iosAppId", "app.ios", "Enter the numeric App Store id."));
        if (store != null && (!UrlGuard.isPublicHttps(store, 500) || !"apps.apple.com".equals(UrlGuard.host(store)) || (iosId != null && !store.contains(iosId))))
            errs.add(new FieldErr("appStoreUrl", "app.store", "Enter the numeric App Store id."));
        if (pkg == null && iosId == null) errs.add(new FieldErr("androidPackage", "app.store.required", "Add an Android package or an iPhone App Store id."));
        String web = Norm.text(r.webFallbackUrl());
        if (web == null || !UrlGuard.isPublicHttps(web, 500)) errs.add(new FieldErr("webFallbackUrl", "app.web", "Add a link for people not on Android or iPhone."));
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        if (store == null && iosId != null) store = "https://apps.apple.com/app/id" + iosId;
        if (play == null && pkg != null) play = "https://play.google.com/store/apps/details?id=" + pkg;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("android_package", pkg);
        m.put("play_store_url", play);
        m.put("ios_app_id", iosId);
        m.put("app_store_url", store);
        m.put("web_fallback_url", web);
        m.put("icon_url", Norm.text(r.iconUrl()));
        return m;
    }

    static String queryParam(String url, String key) {
        try {
            String q = URI.create(url).getRawQuery();
            if (q == null) return null;
            for (String p : q.split("&")) {
                String[] kv = p.split("=", 2);
                if (kv[0].equals(key) && kv.length == 2) return java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // invalid url handled by caller
        }
        return null;
    }

    public Map<String, Object> create(long ws, long userId, AppRequest r) {
        plans.requireFeature(ws, "app_promotion");
        Map<String, Object> c = clean(r);
        if (c.get("android_package") != null && db.exists("select 1 from apps where workspace_id=:ws and android_package=:p", "ws", ws, "p", c.get("android_package")))
            throw ApiException.duplicate("androidPackage", "app.exists", "This app is already added.");
        List<Object> kv = new ArrayList<>(List.of("workspace_id", ws));
        c.forEach((k, v) -> {
            kv.add(k);
            kv.add(v);
        });
        long id = db.insertInto("apps", kv.toArray());
        links.create(ws, userId, new LinkService.LinkRequest("APP", null, id, null, null, "app", "link", "app_promo", null));
        audit.log("APP_CREATED", "apps", id);
        return get(ws, id);
    }

    public Map<String, Object> update(long ws, long id, AppRequest r) {
        get(ws, id);
        Map<String, Object> c = clean(r);
        if (c.get("android_package") != null && db.exists("select 1 from apps where workspace_id=:ws and android_package=:p and id<>:id", "ws", ws, "p", c.get("android_package"), "id", id))
            throw ApiException.duplicate("androidPackage", "app.exists", "This app is already added.");
        db.updateTable("apps", "id", id, c, "workspace_id", ws);
        links.evictApp(id);
        return get(ws, id);
    }

    public Map<String, Object> get(long ws, long id) {
        return db.one("select a.*, (select code from short_links l where l.app_id=a.id and l.type='APP' order by l.id limit 1) as smart_code from apps a where a.id=:id and a.workspace_id=:ws",
                "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("App"));
    }

    public void delete(long ws, long id) {
        get(ws, id);
        if (db.exists("select 1 from offers where app_id=:id and status in ('ACTIVE','PAUSED')", "id", id)) throw ApiException.state("This app is used by a live offer.");
        db.update("update short_links set is_active=0, app_id=null where app_id=:id", "id", id);
        db.update("delete from apps where id=:id and workspace_id=:ws", "id", id, "ws", ws);
    }
}
