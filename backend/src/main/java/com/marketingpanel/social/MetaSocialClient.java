package com.marketingpanel.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.config.Settings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;

/** Graph API publisher: Facebook /feed and /photos, Instagram /media + /media_publish (single image, carousel, video). */
@Component
@ConditionalOnProperty(name = "app.social.mock", havingValue = "false")
public class MetaSocialClient implements SocialClient {
    private final Settings settings;
    private final RestClient http = RestClient.create();

    public MetaSocialClient(Settings settings) {
        this.settings = settings;
    }

    private JsonNode post(String path, LinkedMultiValueMap<String, String> form) {
        try {
            return http.post().uri(settings.graphUrl + path).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new SocialException("Meta error " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
        }
    }

    @Override
    public String publish(PublishRequest r) {
        if ("X".equals(r.platform())) return publishX(r);
        if ("LINKEDIN".equals(r.platform())) return publishLinkedIn(r);
        if ("YOUTUBE".equals(r.platform())) throw new SocialException("YouTube upload is not available yet. Upload the video in YouTube Studio.");
        LinkedMultiValueMap<String, String> f = new LinkedMultiValueMap<>();
        f.add("access_token", r.token());
        if ("FACEBOOK".equals(r.platform())) {
            if (r.media().isEmpty()) {
                f.add("message", r.caption() == null ? "" : r.caption());
                return post("/" + r.externalId() + "/feed", f).path("id").asText();
            }
            f.add("url", r.media().get(0));
            if (r.caption() != null) f.add("caption", r.caption());
            return post("/" + r.externalId() + "/photos", f).path("id").asText();
        }
        // Instagram
        if ("CAROUSEL".equals(r.postType())) {
            List<String> ids = new ArrayList<>();
            for (String url : r.media()) {
                LinkedMultiValueMap<String, String> c = new LinkedMultiValueMap<>();
                c.add("access_token", r.token());
                c.add("image_url", url);
                c.add("is_carousel_item", "true");
                ids.add(post("/" + r.externalId() + "/media", c).path("id").asText());
            }
            f.add("media_type", "CAROUSEL");
            f.add("children", String.join(",", ids));
        } else if ("VIDEO".equals(r.postType())) {
            f.add("media_type", "REELS");
            f.add("video_url", r.media().get(0));
        } else {
            f.add("image_url", r.media().get(0));
        }
        if (r.caption() != null) f.add("caption", r.caption());
        String creation = post("/" + r.externalId() + "/media", f).path("id").asText();
        LinkedMultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        p.add("access_token", r.token());
        p.add("creation_id", creation);
        return post("/" + r.externalId() + "/media_publish", p).path("id").asText();
    }

    private JsonNode json(String uri, String token, Object body, String... extraHeaders) {
        try {
            var spec = http.post().uri(uri).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
            for (int i = 0; i + 1 < extraHeaders.length; i += 2) spec = spec.header(extraHeaders[i], extraHeaders[i + 1]);
            return spec.body(body).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new SocialException(uri.contains("twitter") || uri.contains("x.com") ? "X error " : "LinkedIn error " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
        }
    }

    /** X API v2, text post (user-context OAuth 2.0 token with tweet.write). Media posts need the upload API and are not supported here. */
    private String publishX(PublishRequest r) {
        String text = r.caption() == null ? "" : r.caption();
        if (!r.media().isEmpty()) text = (text + "\n" + r.media().get(0)).trim();
        return json("https://api.x.com/2/tweets", r.token(), java.util.Map.of("text", text)).path("data").path("id").asText();
    }

    /** LinkedIn UGC post as a member or organisation (externalId is the full URN, e.g. urn:li:organization:123). */
    private String publishLinkedIn(PublishRequest r) {
        java.util.Map<String, Object> content = new java.util.LinkedHashMap<>();
        content.put("shareCommentary", java.util.Map.of("text", r.caption() == null ? "" : r.caption()));
        content.put("shareMediaCategory", "NONE");
        if (!r.media().isEmpty()) { // link share
            content.put("shareMediaCategory", "ARTICLE");
            content.put("media", java.util.List.of(java.util.Map.of("status", "READY", "originalUrl", r.media().get(0))));
        }
        java.util.Map<String, Object> body = java.util.Map.of("author", r.externalId(), "lifecycleState", "PUBLISHED",
                "specificContent", java.util.Map.of("com.linkedin.ugc.ShareContent", content),
                "visibility", java.util.Map.of("com.linkedin.ugc.MemberNetworkVisibility", "PUBLIC"));
        return json("https://api.linkedin.com/v2/ugcPosts", r.token(), body, "X-Restli-Protocol-Version", "2.0.0").path("id").asText();
    }

    @Override
    public String sendMessage(String platform, String pageId, String token, String recipientId, String text) {
        if (!List.of("FACEBOOK", "INSTAGRAM").contains(platform)) throw new SocialException("Direct messages are not supported for " + platform + ".");
        LinkedMultiValueMap<String, String> f = new LinkedMultiValueMap<>();
        f.add("access_token", token);
        f.add("recipient", "{\"id\":\"" + recipientId.replaceAll("[^A-Za-z0-9_-]", "") + "\"}");
        f.add("message", new com.fasterxml.jackson.databind.node.ObjectNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance).put("text", text).toString());
        f.add("messaging_type", "RESPONSE");
        return post("/" + pageId + "/messages", f).path("message_id").asText();
    }
}
