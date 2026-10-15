package com.marketingpanel.engage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Port to Web Push delivery. */
public interface PushSender {
    /** Returns false when the subscription is gone (HTTP 404/410) and should be deactivated. */
    boolean send(String endpoint, String p256dh, String auth, String title, String body, String url);

    /**
     * Sandbox sender: logs the delivery. A real sender needs VAPID keys and RFC 8291 payload encryption (for example the
     * web-push library); provide it as a bean of this type to replace this one.
     */
    @Component
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(value = PushSender.class, ignored = LogPushSender.class)
    class LogPushSender implements PushSender {
        private static final Logger log = LoggerFactory.getLogger(LogPushSender.class);

        @Override
        public boolean send(String endpoint, String p256dh, String auth, String title, String body, String url) {
            log.info("Web push (sandbox) to {}… '{}'", endpoint.substring(0, Math.min(40, endpoint.length())), title);
            return true;
        }
    }
}
