package com.marketingpanel.whatsapp;

import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/** Shared access to a workspace's connected number: credentials, per-number rate limit and the send call. */
@Service
public class WaGateway {
    private final Db db;
    private final Crypto crypto;
    private final WhatsAppClient client;
    private final RateLimiterRegistry limiters;

    public WaGateway(Db db, Crypto crypto, WhatsAppClient client, Settings settings) {
        this.db = db;
        this.crypto = crypto;
        this.client = client;
        this.limiters = RateLimiterRegistry.of(RateLimiterConfig.custom().limitForPeriod(settings.waRatePerSecond)
                .limitRefreshPeriod(Duration.ofSeconds(1)).timeoutDuration(Duration.ofSeconds(30)).build());
    }

    public WhatsAppClient client() {
        return client;
    }

    public Row account(long ws) {
        return db.row("select * from wa_accounts where workspace_id=:ws", "ws", ws).orElse(null);
    }

    /** Connected number or WHATSAPP_NOT_CONNECTED. */
    public Row connected(long ws) {
        Row a = account(ws);
        if (a == null || !"CONNECTED".equals(a.s("status"))) throw new ApiException(ErrorCode.WHATSAPP_NOT_CONNECTED, "Connect your WhatsApp number first.");
        return a;
    }

    /** Campaign-level checks: connected and quality not RED. */
    public Row sendable(long ws) {
        Row a = connected(ws);
        if ("RED".equals(a.s("quality_rating")))
            throw new ApiException(ErrorCode.WHATSAPP_NOT_CONNECTED, "Your number's quality rating is RED. New campaigns are blocked until it recovers.");
        return a;
    }

    public String token(Row account) {
        return crypto.decrypt(account.bytes("access_token_enc"));
    }

    public static String digits(String phoneE164) {
        return phoneE164.replace("+", "");
    }

    public String sendTemplate(Row account, WhatsAppClient.TemplateMessage m, String phoneE164) {
        limiters.rateLimiter(account.s("phone_number_id")).acquirePermission();
        return client.sendTemplate(token(account), account.s("phone_number_id"), digits(phoneE164), m);
    }

    public String sendText(Row account, String phoneE164, String text) {
        limiters.rateLimiter(account.s("phone_number_id")).acquirePermission();
        return client.sendText(token(account), account.s("phone_number_id"), digits(phoneE164), text);
    }

    public String sendMedia(Row account, String phoneE164, String type, String url, String caption) {
        limiters.rateLimiter(account.s("phone_number_id")).acquirePermission();
        return client.sendMedia(token(account), account.s("phone_number_id"), digits(phoneE164), type, url, caption);
    }

    /** Builds the template message for one contact from the variable mapping. */
    public static WhatsAppClient.TemplateMessage build(Row template, List<String> params, String headerMediaUrl, List<String> urlSuffixes) {
        String headerType = template.s("header_type");
        return new WhatsAppClient.TemplateMessage(template.s("name"), template.s("language"), params, headerType,
                headerMediaUrl != null ? headerMediaUrl : template.s("header_media_url"), null, urlSuffixes);
    }
}
