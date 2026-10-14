package com.marketingpanel.whatsapp;

import com.marketingpanel.common.Ids;
import com.marketingpanel.jobs.JobService;
import com.marketingpanel.common.Time;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Sandbox adapter used until a Meta app is configured (app.whatsapp.mock=true, the default).
 * Sends always succeed, except to numbers ending in 9999 which fail like an undeliverable number.
 * A follow-up job simulates the delivered/read webhooks so the UI shows realistic progress.
 */
@Component
@ConditionalOnProperty(name = "app.whatsapp.mock", havingValue = "true", matchIfMissing = true)
public class MockWhatsAppClient implements WhatsAppClient {
    private final JobService jobs;

    public MockWhatsAppClient(JobService jobs) {
        this.jobs = jobs;
    }

    @Override
    public String exchangeCode(String code) {
        if (code == null || code.isBlank()) throw new WaException("190", "Invalid code", false);
        return "mock-token-" + Ids.randomCode(12);
    }

    @Override
    public PhoneInfo fetchPhone(String token, String phoneNumberId) {
        return new PhoneInfo("+91 90000 " + phoneNumberId.substring(Math.max(0, phoneNumberId.length() - 5)), "Mock Business", "GREEN", "TIER_1K");
    }

    @Override
    public void subscribeApp(String token, String wabaId) {}

    private String simulate(String to) {
        if (to.endsWith("9999")) throw new WaException("131026", "Message undeliverable", false);
        String wamid = "wamid.mock." + Ids.ulid();
        jobs.enqueue("WA_MOCK_STATUS", Map.of("wamid", wamid, "status", "delivered"), Time.now().plusSeconds(2));
        jobs.enqueue("WA_MOCK_STATUS", Map.of("wamid", wamid, "status", "read"), Time.now().plusSeconds(8));
        return wamid;
    }

    @Override
    public String sendTemplate(String token, String phoneNumberId, String to, TemplateMessage m) {
        return simulate(to);
    }

    @Override
    public String sendText(String token, String phoneNumberId, String to, String text) {
        return simulate(to);
    }

    @Override
    public String sendMedia(String token, String phoneNumberId, String to, String type, String url, String caption) {
        return simulate(to);
    }

    @Override
    public String submitTemplate(String token, String wabaId, TemplateSpec spec) {
        String id = "mock" + System.nanoTime();
        jobs.enqueue("WA_MOCK_TEMPLATE", Map.of("metaId", id, "name", spec.name(), "approve", !spec.name().contains("reject")), Time.now().plusSeconds(5));
        return id;
    }

    @Override
    public List<TemplateStatus> listTemplates(String token, String wabaId) {
        return List.of();
    }
}
