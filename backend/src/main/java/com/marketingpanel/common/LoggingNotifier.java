package com.marketingpanel.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/** Default notifier: logs a masked line. Replace with an SMS/e-mail provider bean in production. */
@Component
@ConditionalOnMissingBean(value = Notifier.class, ignored = LoggingNotifier.class)
public class LoggingNotifier implements Notifier {
    private static final Logger log = LoggerFactory.getLogger(LoggingNotifier.class);

    @Override
    public void sms(String phone, String text) {
        log.info("SMS to {} ({} chars)", Phones.mask(phone), text.length());
        log.debug("SMS body for {}: {}", Phones.mask(phone), text);
    }

    @Override
    public void email(String to, String subject, String body) {
        log.info("Email to {} subject='{}'", to == null ? null : to.replaceAll("(?<=.).(?=[^@]*@)", "*"), subject);
        log.debug("Email body: {}", body);
    }
}
