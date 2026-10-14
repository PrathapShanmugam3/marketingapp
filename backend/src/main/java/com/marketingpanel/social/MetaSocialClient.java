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
}
