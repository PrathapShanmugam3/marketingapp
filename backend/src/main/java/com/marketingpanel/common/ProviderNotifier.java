package com.marketingpanel.common;

import com.marketingpanel.config.Settings;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Real delivery when NOTIFY_PROVIDER=real: SMTP for e-mail (SMTP_HOST...) and a generic HTTP SMS gateway (SMS_GATEWAY_URL, SMS_API_KEY).
 * The gateway contract is {"to": "+91...", "text": "...", "sender": "HEADER"} with a Bearer key; adapt {@link #sms} for providers that differ.
 */
@Component
@ConditionalOnProperty(name = "app.notify.provider", havingValue = "real")
public class ProviderNotifier implements Notifier {
    private static final Logger log = LoggerFactory.getLogger(ProviderNotifier.class);
    private final ObjectProvider<JavaMailSender> mail;
    private final String from;
    private final String smsUrl;
    private final String smsKey;
    private final String sender;
    private final RestClient http = RestClient.create();

    public ProviderNotifier(ObjectProvider<JavaMailSender> mail, Settings settings,
                            @Value("${app.notify.mail-from}") String from, @Value("${app.sms.url:}") String smsUrl,
                            @Value("${app.sms.api-key:}") String smsKey, @Value("${app.sms.sender-id:}") String sender) {
        this.mail = mail;
        this.from = from;
        this.smsUrl = smsUrl;
        this.smsKey = smsKey;
        this.sender = sender;
    }

    @Override
    public void sms(String phone, String text) {
        if (smsUrl.isBlank()) throw new IllegalStateException("SMS gateway is not configured (SMS_GATEWAY_URL).");
        http.post().uri(smsUrl).header("Authorization", "Bearer " + smsKey).body(Map.of("to", phone, "text", text, "sender", sender)).retrieve().toBodilessEntity();
        log.info("SMS sent to {}", Phones.mask(phone));
    }

    @Override
    public void email(String to, String subject, String body) {
        send(to, subject, body, false);
    }

    @Override
    public void emailHtml(String to, String subject, String html) {
        send(to, subject, html, true);
    }

    private void send(String to, String subject, String body, boolean html) {
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) throw new IllegalStateException("E-mail is not configured (SMTP_HOST).");
        try {
            MimeMessage m = sender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(m, "UTF-8");
            h.setFrom(from);
            h.setTo(to);
            h.setSubject(subject);
            h.setText(body, html);
            sender.send(m);
        } catch (Exception e) {
            throw new IllegalStateException("E-mail could not be sent: " + e.getMessage(), e);
        }
    }
}
