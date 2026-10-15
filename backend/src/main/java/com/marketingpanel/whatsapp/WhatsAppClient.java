package com.marketingpanel.whatsapp;

import java.util.List;

/** Port to the WhatsApp Cloud API. MetaCloudClient is the real adapter; MockWhatsAppClient is used when app.whatsapp.mock=true. */
public interface WhatsAppClient {
    record PhoneInfo(String displayPhone, String verifiedName, String qualityRating, String messagingLimit) {}

    record TemplateStatus(String metaId, String name, String language, String status, String reason) {}

    /** Message body parameters for a template send. */
    record TemplateMessage(String name, String language, List<String> bodyParams, String headerType, String headerMediaUrl,
                           String headerTextParam, List<String> urlButtonSuffixes) {}

    String exchangeCode(String code);

    PhoneInfo fetchPhone(String token, String phoneNumberId);

    void subscribeApp(String token, String wabaId);

    /** Returns the wamid. Throws WaException; retryable() tells the caller whether to retry later. */
    String sendTemplate(String token, String phoneNumberId, String toDigits, TemplateMessage m);

    String sendText(String token, String phoneNumberId, String toDigits, String text);

    String sendMedia(String token, String phoneNumberId, String toDigits, String type, String url, String caption);

    /** Submits a template for approval, returns Meta's template id. */
    String submitTemplate(String token, String wabaId, TemplateSpec spec);

    List<TemplateStatus> listTemplates(String token, String wabaId);
}
