package com.marketingpanel.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.common.ApiException;
import com.marketingpanel.config.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** CAPTCHA is verified server-side (Cloudflare Turnstile). With no secret configured any non-empty token passes (dev). */
@Component
public class CaptchaVerifier {
    private static final Logger log = LoggerFactory.getLogger(CaptchaVerifier.class);
    private final Settings settings;
    private final RestClient http = RestClient.create();

    public CaptchaVerifier(Settings settings) {
        this.settings = settings;
    }

    public void verify(String token, String ip, String field) {
        boolean ok;
        if (token == null || token.isBlank()) {
            ok = false;
        } else if (settings.captchaSecret.isBlank()) {
            ok = true;
        } else {
            try {
                var form = new LinkedMultiValueMap<String, String>();
                form.add("secret", settings.captchaSecret);
                form.add("response", token);
                if (ip != null) form.add("remoteip", ip);
                JsonNode n = http.post().uri("https://challenges.cloudflare.com/turnstile/v0/siteverify")
                        .body(form).retrieve().body(JsonNode.class);
                ok = n != null && n.path("success").asBoolean(false);
            } catch (Exception e) {
                log.warn("CAPTCHA verification failed to run: {}", e.toString());
                ok = false;
            }
        }
        if (!ok) throw ApiException.invalid(field, "captcha.invalid", "Please complete the check.");
    }
}
