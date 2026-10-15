package com.marketingpanel.common;

/** SMS / e-mail delivery (OTP, invites, invoices). Provider is chosen at deploy time. */
public interface Notifier {
    void sms(String phoneE164, String text);

    void email(String to, String subject, String body);

    /** HTML mail (broadcasts). Providers without HTML support send the markup as text. */
    default void emailHtml(String to, String subject, String html) {
        email(to, subject, html);
    }
}
