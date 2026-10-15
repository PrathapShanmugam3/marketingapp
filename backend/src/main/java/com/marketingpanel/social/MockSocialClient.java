package com.marketingpanel.social;

import com.marketingpanel.common.Ids;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Sandbox publisher: succeeds unless the caption contains the word FAILPUBLISH (used to test retries). */
@Component
@ConditionalOnProperty(name = "app.social.mock", havingValue = "true", matchIfMissing = true)
public class MockSocialClient implements SocialClient {
    @Override
    public String publish(PublishRequest r) {
        if (r.caption() != null && r.caption().contains("FAILPUBLISH")) throw new SocialException("Sandbox failure requested");
        return r.platform().toLowerCase().charAt(0) + "_" + Ids.ulid();
    }

    @Override
    public String sendMessage(String platform, String pageId, String token, String recipientId, String text) {
        if (text != null && text.contains("FAILSEND")) throw new SocialException("Sandbox failure requested");
        return "m_" + Ids.ulid();
    }
}
