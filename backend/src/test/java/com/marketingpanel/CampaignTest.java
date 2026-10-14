package com.marketingpanel;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignTest extends BaseIT {

    private Map<String, Object> campaignBody(String name, long templateId) {
        return Map.of("name", name, "templateId", templateId, "audienceType", "ALL_OPTED_IN",
                "variableMapping", Map.of("1", Map.of("type", "field", "value", "firstName")));
    }

    @Test
    void templateValidationRules() throws Exception {
        Api.Session s = api.signup();
        connectWhatsApp(s);
        var edge = api.post("/api/v1/templates", s.token(), Map.of("name", "bad_one", "category", "UTILITY", "language", "en", "body", "{{1}} is ready", "sampleValues", List.of("x")));
        assertThat(edge.status()).isEqualTo(400);
        var adjacent = api.post("/api/v1/templates", s.token(), Map.of("name", "bad_two", "category", "UTILITY", "language", "en", "body", "Hi {{1}}{{2}} ok", "sampleValues", List.of("x", "y")));
        assertThat(adjacent.status()).isEqualTo(400);
        var gap = api.post("/api/v1/templates", s.token(), Map.of("name", "bad_three", "category", "UTILITY", "language", "en", "body", "Hi {{2}} ok", "sampleValues", List.of("x")));
        assertThat(gap.status()).isEqualTo(400);
        var noSample = api.post("/api/v1/templates", s.token(), Map.of("name", "bad_four", "category", "UTILITY", "language", "en", "body", "Hi {{1}} ok"));
        assertThat(noSample.status()).isEqualTo(400);
        var upper = api.post("/api/v1/templates", s.token(), Map.of("name", "Bad-Name", "category", "UTILITY", "language", "en", "body", "Hello there"));
        assertThat(upper.status()).isEqualTo(400);

        long id = approvedTemplate(s, "diwali_offer", "MARKETING", "Hi {{1}}, our Diwali offer is live. Visit us soon!", List.of("Ravi"));
        var t = api.get("/api/v1/templates/" + id, s.token());
        assertThat(t.text("/footer")).isEqualTo("Reply STOP to unsubscribe"); // auto opt-out footer
        assertThat(t.body().at("/variableCount").asInt()).isEqualTo(1);
        var dup = api.post("/api/v1/templates", s.token(), Map.of("name", "diwali_offer", "category", "MARKETING", "language", "en", "body", "Hello again"));
        assertThat(dup.status()).isEqualTo(409);
    }

    @Test
    void sendCampaignEndToEndWithWalletAndStatuses() throws Exception {
        Api.Session s = api.signup();
        connectWhatsApp(s);
        long tpl = approvedTemplate(s, "festival_offer", "MARKETING", "Hi {{1}}, enjoy 20% off this weekend at our store!", List.of("Ravi"));
        contact(s, "Anu Priya", "9876500101", true);
        contact(s, "Bala Murugan", "9876500102", true);
        contact(s, "Fail Number", "9876509999", true);  // sandbox fails numbers ending in 9999
        contact(s, "Opted Out", "9876500104", false);   // not opted in: never receives marketing

        long cid = api.post("/api/v1/campaigns", s.token(), campaignBody("Weekend sale", tpl)).body().get("id").asLong();
        assertThat(api.post("/api/v1/campaigns", s.token(), campaignBody("weekend SALE", tpl)).status()).isEqualTo(400); // duplicate name

        var est = api.post("/api/v1/campaigns/" + cid + "/estimate", s.token(), null);
        assertThat(est.body().get("recipients").asInt()).isEqualTo(3);
        assertThat(new BigDecimal(est.body().get("cost").asText())).isEqualByComparingTo("3.6");
        assertThat(est.body().get("sufficient").asBoolean()).isFalse();

        var broke = api.post("/api/v1/campaigns/" + cid + "/send", s.token(), null);
        assertThat(broke.status()).isEqualTo(402);
        assertThat(broke.text("/code")).isEqualTo("INSUFFICIENT_BALANCE");

        topUp(s.workspaceId(), "10");
        var sent = api.post("/api/v1/campaigns/" + cid + "/send", s.token(), null);
        assertThat(sent.status()).isEqualTo(200);
        assertThat(sent.text("/status")).isEqualTo("QUEUED");
        assertThat(wallet.balance(s.workspaceId())).isEqualByComparingTo("6.4"); // 10 - 3.60 reserved
        assertThat(api.post("/api/v1/campaigns/" + cid + "/send", s.token(), null).status()).isEqualTo(409); // not a draft anymore

        runJobs(); // batch + simulated delivered/read webhooks
        var c = api.get("/api/v1/campaigns/" + cid, s.token());
        assertThat(c.text("/status")).isEqualTo("COMPLETED");
        assertThat(c.body().at("/totalRecipients").asInt()).isEqualTo(3);
        assertThat(c.body().at("/sentCount").asInt()).isEqualTo(2);
        assertThat(c.body().at("/deliveredCount").asInt()).isEqualTo(2);
        assertThat(c.body().at("/readCount").asInt()).isEqualTo(2);
        assertThat(c.body().at("/failedCount").asInt()).isEqualTo(1);
        assertThat(new BigDecimal(c.body().at("/actualCost").asText())).isEqualByComparingTo("2.4");
        assertThat(wallet.balance(s.workspaceId())).isEqualByComparingTo("7.6"); // failed message refunded
        assertThat(wallet.ledgerDifference(s.workspaceId())).isEqualByComparingTo("0");

        var msgs = api.get("/api/v1/campaigns/" + cid + "/messages?status=FAILED", s.token());
        assertThat(msgs.body().at("/items").size()).isEqualTo(1);
        assertThat(msgs.body().at("/items/0/errorCode").asText()).isEqualTo("131026");
        assertThat(msgs.body().at("/items/0/reason").asText()).contains("not on WhatsApp");
        var csv = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/campaigns/" + cid + "/messages.csv")
                .header("Authorization", "Bearer " + s.token())).andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("Anu Priya").contains("READ");

        // state machine: a completed campaign cannot be paused, resumed or cancelled
        for (String a : List.of("pause", "resume", "cancel")) {
            var r = api.post("/api/v1/campaigns/" + cid + "/" + a, s.token(), null);
            assertThat(r.status()).as(a).isEqualTo(409);
            assertThat(r.text("/code")).isEqualTo("INVALID_STATE");
        }
        var dupl = api.post("/api/v1/campaigns/" + cid + "/duplicate", s.token(), null);
        assertThat(dupl.text("/status")).isEqualTo("DRAFT");
        assertThat(dupl.text("/name")).isEqualTo("Weekend sale (copy)");
    }

    @Test
    void scheduleCancelRefundsAndFrequencyCap() throws Exception {
        Api.Session s = api.signup();
        connectWhatsApp(s);
        long tpl = approvedTemplate(s, "promo_offer", "MARKETING", "Hi {{1}}, a new arrival just landed in store!", List.of("Ravi"));
        contact(s, "Cap Person", "9876500201", true);
        topUp(s.workspaceId(), "20");

        long c1 = api.post("/api/v1/campaigns", s.token(), campaignBody("Scheduled one", tpl)).body().get("id").asLong();
        assertThat(api.post("/api/v1/campaigns/" + c1 + "/schedule", s.token(), null).status()).isEqualTo(400); // needs a time
        var upd = new java.util.HashMap<>(campaignBody("Scheduled one", tpl));
        upd.put("scheduledAt", java.time.Instant.now().plusSeconds(3 * 3600).toString());
        assertThat(api.put("/api/v1/campaigns/" + c1, s.token(), upd).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/campaigns/" + c1 + "/schedule", s.token(), null).text("/status")).isEqualTo("SCHEDULED");
        assertThat(wallet.balance(s.workspaceId())).isEqualByComparingTo("18.8");
        assertThat(api.post("/api/v1/campaigns/" + c1 + "/pause", s.token(), null).status()).isEqualTo(409); // only SENDING can pause
        assertThat(api.post("/api/v1/campaigns/" + c1 + "/cancel", s.token(), null).text("/status")).isEqualTo("CANCELLED");
        assertThat(wallet.balance(s.workspaceId())).isEqualByComparingTo("20"); // full reservation released

        // frequency cap: 2 marketing messages per contact per 24 h
        for (int i = 1; i <= 3; i++) {
            long id = api.post("/api/v1/campaigns", s.token(), campaignBody("Blast " + i, tpl)).body().get("id").asLong();
            assertThat(api.post("/api/v1/campaigns/" + id + "/send", s.token(), null).status()).isEqualTo(200);
            runJobs();
            var r = api.get("/api/v1/campaigns/" + id + "/messages", s.token());
            String st = r.body().at("/items/0/status").asText();
            if (i < 3) assertThat(st).isIn("SENT", "DELIVERED", "READ");
            else {
                assertThat(st).isEqualTo("FAILED");
                assertThat(r.body().at("/items/0/errorCode").asText()).isEqualTo("FREQ_CAP");
            }
        }
        assertThat(wallet.ledgerDifference(s.workspaceId())).isEqualByComparingTo("0");
    }

    @Test
    void metaWebhookStopOptOutInboxAndReplyWindow() throws Exception {
        Api.Session s = api.signup();
        connectWhatsApp(s);
        long cid = contact(s, "Stop Person", "9876500301", true);
        String phoneNumberId = db.scalar("select phone_number_id from wa_accounts where workspace_id=:ws", String.class, "ws", s.workspaceId());

        String payload = om.writeValueAsString(Map.of("object", "whatsapp_business_account", "entry", List.of(Map.of("id", "x", "changes", List.of(Map.of("field", "messages",
                "value", Map.of("metadata", Map.of("phone_number_id", phoneNumberId), "contacts", List.of(Map.of("wa_id", "919876500301", "profile", Map.of("name", "Stop Person"))),
                        "messages", List.of(Map.of("from", "919876500301", "id", "wamid.in.1", "timestamp", String.valueOf(System.currentTimeMillis() / 1000), "type", "text",
                                "text", Map.of("body", "hello shop"))))))))));
        assertThat(api.post("/webhooks/meta", null, payload).status()).isEqualTo(200);
        assertThat(api.post("/webhooks/meta", null, payload).status()).isEqualTo(200); // duplicate delivery is a no-op
        runJobs();
        assertThat(db.count("select count(*) from webhook_events", "x", 1)).isEqualTo(db.count("select count(*) from webhook_events where processed_at is not null", "x", 1));

        var convs = api.get("/api/v1/inbox/conversations", s.token());
        assertThat(convs.body().size()).isEqualTo(1);
        assertThat(convs.body().at("/0/unreadCount").asInt()).isEqualTo(1);
        assertThat(convs.body().at("/0/windowOpen").asBoolean()).isTrue();
        long conv = convs.body().at("/0/id").asLong();
        var msgs = api.get("/api/v1/inbox/conversations/" + conv + "/messages", s.token());
        assertThat(msgs.body().at("/items").size()).isEqualTo(1); // duplicate wamid ignored

        assertThat(api.post("/api/v1/inbox/conversations/" + conv + "/messages", s.token(), Map.of("text", "Thanks, how can we help?")).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/inbox/conversations/" + conv + "/messages", s.token(), Map.of("text", "")).status()).isEqualTo(400);
        db.update("update conversations set last_inbound_at = date_sub(utc_timestamp(3), interval 30 hour) where id=:id", "id", conv);
        var closed = api.post("/api/v1/inbox/conversations/" + conv + "/messages", s.token(), Map.of("text", "Hello?"));
        assertThat(closed.status()).isEqualTo(409);
        assertThat(closed.text("/message")).contains("24-hour window");

        String stop = payload.replace("hello shop", "STOP").replace("wamid.in.1", "wamid.in.2");
        api.post("/webhooks/meta", null, stop);
        runJobs();
        assertThat(api.get("/api/v1/contacts/" + cid, s.token()).text("/optInStatus")).isEqualTo("OPTED_OUT");

        // a verified challenge for Meta's subscription handshake
        var v = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/webhooks/meta?hub.mode=subscribe&hub.verify_token=dev-verify-token&hub.challenge=123")).andReturn().getResponse();
        assertThat(v.getContentAsString()).isEqualTo("123");
        var bad = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/webhooks/meta?hub.mode=subscribe&hub.verify_token=nope&hub.challenge=123")).andReturn().getResponse();
        assertThat(bad.getStatus()).isEqualTo(403);
    }
}
