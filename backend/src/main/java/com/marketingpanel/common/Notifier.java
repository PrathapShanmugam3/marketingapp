package com.marketingpanel.common;

/** SMS / e-mail delivery (OTP, invites, invoices). Provider is chosen at deploy time. */
public interface Notifier {
    void sms(String phoneE164, String text);

    void email(String to, String subject, String body);
}
