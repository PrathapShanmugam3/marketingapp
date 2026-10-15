package com.marketingpanel.affiliate;

import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Public offer click redirect (/o/{offer}/{code}) and the signed postback endpoint (/pb). */
@RestController
public class TrackingController {
    private static final Pattern SUB = Pattern.compile("^[A-Za-z0-9_-]{1,100}$");
    private static final Pattern BOT = Pattern.compile("(?i)(bot|crawler|spider|facebookexternalhit|whatsapp|telegram|slackbot|preview|curl|wget|python-requests|headless)");
    private final Db db;
    private final Settings settings;
    private final RateLimiter limiter;
    private final PostbackService postbacks;
    private final IpIntelligence ipIntel;

    public TrackingController(Db db, Settings settings, RateLimiter limiter, PostbackService postbacks, IpIntelligence ipIntel) {
        this.db = db;
        this.settings = settings;
        this.limiter = limiter;
        this.postbacks = postbacks;
        this.ipIntel = ipIntel;
    }

    private static String platform(String ua) {
        if (ua == null) return "OTHER";
        if (ua.contains("Android")) return "ANDROID";
        if (ua.contains("iPhone") || ua.contains("iPad")) return "IOS";
        if (ua.contains("Windows") || ua.contains("Macintosh") || ua.contains("X11") || ua.contains("Linux")) return "DESKTOP";
        return "OTHER";
    }

    @GetMapping("/o/{offerId}/{code}")
    public ResponseEntity<String> click(@PathVariable long offerId, @PathVariable String code, @RequestParam(required = false) String sub1, @RequestParam(required = false) String sub2,
                                        @RequestHeader(name = "User-Agent", required = false) String ua, HttpServletRequest req) {
        String ip = ClientInfo.ip(req);
        if (!limiter.tryConsume("redir:" + ip, 30, Duration.ofSeconds(1))) return ResponseEntity.status(429).header("Retry-After", "1").build();
        Row offer = db.row("select o.*, a.android_package, a.play_store_url, a.app_store_url, a.ios_app_id from offers o left join apps a on a.id=o.app_id where o.id=:id", "id", offerId).orElse(null);
        boolean live = offer != null && "ACTIVE".equals(offer.s("status")) && (offer.t("starts_at") == null || !offer.t("starts_at").isAfter(Time.now()))
                && (offer.t("ends_at") == null || offer.t("ends_at").isAfter(Time.now()));
        if (!live) return ResponseEntity.status(410).contentType(MediaType.TEXT_HTML).body(Html.ended("This offer is no longer available."));
        Row promoter = db.row("select id, status from promoters where referral_code=:c", "c", code.toUpperCase()).orElse(null);
        String s1 = sub1 != null && SUB.matcher(sub1).matches() ? sub1 : null, s2 = sub2 != null && SUB.matcher(sub2).matches() ? sub2 : null;
        String clickId = Ids.ulid();
        String platform = platform(ua);
        boolean tracked = promoter == null || "ACTIVE".equals(promoter.s("status")) || "ON_HOLD".equals(promoter.s("status")); // blocked promoters earn nothing
        if (tracked) {
            db.insertInto("tracking_clicks", "click_id", clickId, "offer_id", offerId, "promoter_id", promoter == null ? null : promoter.l("id"), "sub1", s1, "sub2", s2,
                    "platform", platform, "ip_hash", ClientInfo.ipHash(ip, settings.ipHashSalt), "user_agent", ua == null ? null : (ua.length() > 255 ? ua.substring(0, 255) : ua),
                    "is_bot", ua == null || BOT.matcher(ua).find(), "is_vpn", ipIntel.isVpn(ip));
        }
        String target;
        if ("ANDROID".equals(platform) && offer.s("play_store_url") != null) {
            target = offer.s("play_store_url") + (offer.s("play_store_url").contains("?") ? "&" : "?") + "referrer=" + URLEncoder.encode("click_id=" + clickId + "&utm_source=promoter", StandardCharsets.UTF_8);
        } else if ("IOS".equals(platform) && offer.s("app_store_url") != null) {
            target = offer.s("app_store_url") + (offer.s("app_store_url").contains("?") ? "&" : "?") + "ct=" + clickId;
        } else {
            target = offer.s("landing_url").replace("{click_id}", clickId).replace("{sub1}", s1 == null ? "" : s1).replace("{sub2}", s2 == null ? "" : s2);
        }
        return ResponseEntity.status(302).location(URI.create(target)).cacheControl(CacheControl.noStore()).build();
    }

    @RequestMapping(value = "/pb", method = {RequestMethod.GET, RequestMethod.POST})
    public Map<String, Object> postback(@RequestParam Map<String, String> params, HttpServletRequest req) {
        String ip = ClientInfo.ip(req);
        if (!limiter.tryConsume("pb:" + ip, 100, Duration.ofSeconds(1))) return Map.of("result", "RATE_LIMITED");
        PostbackService.Result r = postbacks.process(params, ip, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", r.code());
        return out; // always HTTP 200
    }
}
