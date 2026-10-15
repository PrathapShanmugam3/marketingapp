package com.marketingpanel.social;

import java.util.List;

/** Port to Facebook Pages / Instagram publishing (Meta Graph API). */
public interface SocialClient {
    record PublishRequest(String platform, String externalId, String token, String caption, String postType, List<String> media) {}

    /** Returns the platform post id; throws SocialException on failure. */
    String publish(PublishRequest r);

    /** Sends a direct message (Messenger / Instagram DM) as the page. Returns the message id. */
    default String sendMessage(String platform, String pageId, String token, String recipientId, String text) {
        throw new SocialException("Direct messages are not supported for " + platform + ".");
    }

    class SocialException extends RuntimeException {
        public SocialException(String msg) {
            super(msg);
        }
    }
}
