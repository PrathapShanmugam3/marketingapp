package com.marketingpanel.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.common.Json;
import com.marketingpanel.config.Settings;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.*;

/** Real Meta WhatsApp Cloud API adapter (Graph API). A circuit breaker protects against Meta outages. */
@Component
@ConditionalOnProperty(name = "app.whatsapp.mock", havingValue = "false")
public class MetaCloudClient implements WhatsAppClient {
    private final Settings settings;
    private final RestClient http = RestClient.builder().build();
    private final CircuitBreaker breaker = CircuitBreaker.of("meta", CircuitBreakerConfig.custom()
            .failureRateThreshold(50).minimumNumberOfCalls(20).slidingWindowSize(50)
            .waitDurationInOpenState(Duration.ofSeconds(60)).build());

    public MetaCloudClient(Settings settings) {
        this.settings = settings;
    }

    private JsonNode call(java.util.function.Supplier<JsonNode> s) {
        try {
            return breaker.executeSupplier(s);
        } catch (CallNotPermittedException e) {
            throw new WaException("CIRCUIT_OPEN", "WhatsApp is temporarily unavailable.", true);
        } catch (RestClientResponseException e) {
            JsonNode err = null;
            try {
                err = Json.tree(e.getResponseBodyAsString()).path("error");
            } catch (Exception ignored) {
                // not JSON
            }
            int st = e.getStatusCode().value();
            String code = err != null && err.has("code") ? err.get("code").asText() : String.valueOf(st);
            String msg = err != null && err.has("message") ? err.get("message").asText() : e.getMessage();
            boolean retry = st == 429 || st >= 500 || "130429".equals(code) || "131056".equals(code);
            throw new WaException(code, msg, retry);
        } catch (WaException e) {
            throw e;
        } catch (Exception e) {
            throw new WaException("NETWORK", e.toString(), true);
        }
    }

    private RestClient.RequestBodySpec post(String path, String token) {
        return http.post().uri(settings.graphUrl + path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
    }

    @Override
    public String exchangeCode(String code) {
        JsonNode n = call(() -> http.get().uri(settings.graphUrl + "/oauth/access_token?client_id={id}&client_secret={s}&code={c}",
                settings.metaAppId, settings.metaAppSecret, code).retrieve().body(JsonNode.class));
        return n.path("access_token").asText();
    }

    @Override
    public PhoneInfo fetchPhone(String token, String phoneNumberId) {
        JsonNode n = call(() -> http.get().uri(settings.graphUrl + "/" + phoneNumberId + "?fields=display_phone_number,verified_name,quality_rating,messaging_limit_tier")
                .header("Authorization", "Bearer " + token).retrieve().body(JsonNode.class));
        String q = n.path("quality_rating").asText("UNKNOWN").toUpperCase();
        return new PhoneInfo(n.path("display_phone_number").asText(), n.path("verified_name").asText(null),
                List.of("GREEN", "YELLOW", "RED").contains(q) ? q : "UNKNOWN", n.path("messaging_limit_tier").asText(null));
    }

    @Override
    public void subscribeApp(String token, String wabaId) {
        call(() -> post("/" + wabaId + "/subscribed_apps", token).body(Map.of()).retrieve().body(JsonNode.class));
    }

    @Override
    public String sendTemplate(String token, String phoneNumberId, String to, TemplateMessage m) {
        List<Map<String, Object>> comps = new ArrayList<>();
        if (m.headerType() != null && !"NONE".equals(m.headerType())) {
            if ("TEXT".equals(m.headerType())) {
                if (m.headerTextParam() != null)
                    comps.add(Map.of("type", "header", "parameters", List.of(Map.of("type", "text", "text", m.headerTextParam()))));
            } else {
                String t = m.headerType().toLowerCase();
                comps.add(Map.of("type", "header", "parameters", List.of(Map.of("type", t, t, Map.of("link", m.headerMediaUrl())))));
            }
        }
        if (m.bodyParams() != null && !m.bodyParams().isEmpty()) {
            List<Map<String, Object>> ps = new ArrayList<>();
            for (String p : m.bodyParams()) ps.add(Map.of("type", "text", "text", p));
            comps.add(Map.of("type", "body", "parameters", ps));
        }
        if (m.urlButtonSuffixes() != null) {
            int i = 0;
            for (String suffix : m.urlButtonSuffixes()) {
                comps.add(Map.of("type", "button", "sub_type", "url", "index", String.valueOf(i++), "parameters", List.of(Map.of("type", "text", "text", suffix))));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", to);
        body.put("type", "template");
        body.put("template", Map.of("name", m.name(), "language", Map.of("code", m.language()), "components", comps));
        JsonNode n = call(() -> post("/" + phoneNumberId + "/messages", token).body(body).retrieve().body(JsonNode.class));
        return n.path("messages").path(0).path("id").asText();
    }

    @Override
    public String sendText(String token, String phoneNumberId, String to, String text) {
        Map<String, Object> body = Map.of("messaging_product", "whatsapp", "to", to, "type", "text", "text", Map.of("body", text, "preview_url", false));
        JsonNode n = call(() -> post("/" + phoneNumberId + "/messages", token).body(body).retrieve().body(JsonNode.class));
        return n.path("messages").path(0).path("id").asText();
    }

    @Override
    public String sendMedia(String token, String phoneNumberId, String to, String type, String url, String caption) {
        Map<String, Object> media = new LinkedHashMap<>();
        media.put("link", url);
        if (caption != null && !"document".equals(type) || caption != null) media.put("caption", caption);
        Map<String, Object> body = Map.of("messaging_product", "whatsapp", "to", to, "type", type, type, media);
        JsonNode n = call(() -> post("/" + phoneNumberId + "/messages", token).body(body).retrieve().body(JsonNode.class));
        return n.path("messages").path(0).path("id").asText();
    }

    @Override
    public String submitTemplate(String token, String wabaId, TemplateSpec s) {
        List<Map<String, Object>> comps = new ArrayList<>();
        if (!"NONE".equals(s.headerType())) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("type", "HEADER");
            h.put("format", s.headerType());
            if ("TEXT".equals(s.headerType())) h.put("text", s.headerText());
            else h.put("example", Map.of("header_handle", List.of(s.headerMediaUrl())));
            comps.add(h);
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "BODY");
        b.put("text", s.body());
        if (s.samples() != null && !s.samples().isEmpty()) b.put("example", Map.of("body_text", List.of(s.samples())));
        comps.add(b);
        if (s.footer() != null && !s.footer().isBlank()) comps.add(Map.of("type", "FOOTER", "text", s.footer()));
        if (s.buttons() != null && !s.buttons().isEmpty()) comps.add(Map.of("type", "BUTTONS", "buttons", s.buttons()));
        Map<String, Object> body = Map.of("name", s.name(), "category", s.category(), "language", s.language(), "components", comps);
        JsonNode n = call(() -> post("/" + wabaId + "/message_templates", token).body(body).retrieve().body(JsonNode.class));
        return n.path("id").asText();
    }

    @Override
    public List<TemplateStatus> listTemplates(String token, String wabaId) {
        JsonNode n = call(() -> http.get().uri(settings.graphUrl + "/" + wabaId + "/message_templates?fields=name,status,language,id,rejected_reason&limit=200")
                .header("Authorization", "Bearer " + token).retrieve().body(JsonNode.class));
        List<TemplateStatus> out = new ArrayList<>();
        for (JsonNode t : n.path("data")) out.add(new TemplateStatus(t.path("id").asText(), t.path("name").asText(), t.path("language").asText(),
                t.path("status").asText(), t.path("rejected_reason").asText(null)));
        return out;
    }
}
