package com.marketingpanel;

import com.marketingpanel.growth.AutomationEngine;
import com.marketingpanel.growth.LinkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GrowthTest extends BaseIT {
    @Autowired LinkService links;
    @Autowired AutomationEngine engine;

    @Test
    void smartLinksTrackClicksAndSkipBots() throws Exception {
        Api.Session s = api.signup();
        var app = api.post("/api/v1/apps", s.token(), Map.of("name", "My Shop", "androidPackage", "in.myshop.app",
                "playStoreUrl", "https://play.google.com/store/apps/details?id=in.myshop.app", "iosAppId", "123456789", "webFallbackUrl", "https://myshop.in"));
        assertThat(app.status()).isEqualTo(200);
        String code = app.text("/smartCode");
        assertThat(code).hasSize(7);
        assertThat(api.post("/api/v1/apps", s.token(), Map.of("name", "Bad", "androidPackage", "in.bad.app",
                "playStoreUrl", "https://play.google.com/store/apps/details?id=other.app", "webFallbackUrl", "https://x.in")).status()).isEqualTo(400);

        String android = "Mozilla/5.0 (Linux; Android 14) Chrome/120";
        var r1 = mvc.perform(MockMvcRequestBuilders.get("/l/" + code).header("User-Agent", android)).andReturn().getResponse();
        assertThat(r1.getStatus()).isEqualTo(302);
        assertThat(r1.getHeader("Location")).startsWith("https://play.google.com/store/apps/details?id=in.myshop.app&referrer=utm_source");
        var r2 = mvc.perform(MockMvcRequestBuilders.get("/l/" + code).header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17)")).andReturn().getResponse();
        assertThat(r2.getHeader("Location")).startsWith("https://apps.apple.com/app/id123456789");
        var r3 = mvc.perform(MockMvcRequestBuilders.get("/l/" + code).header("User-Agent", "Mozilla/5.0 (Windows NT 10.0)")).andReturn().getResponse();
        assertThat(r3.getHeader("Location")).isEqualTo("https://myshop.in");
        mvc.perform(MockMvcRequestBuilders.get("/l/" + code).header("User-Agent", "WhatsApp/2.23 A")).andReturn(); // link preview bot: redirected, not counted
        links.awaitClicks();
        long id = db.scalar("select id from short_links where code=:c", Long.class, "c", code);
        var stats = api.get("/api/v1/links/" + id + "/stats", s.token());
        assertThat(stats.body().get("clicks").asInt()).isEqualTo(3);

        var custom = api.post("/api/v1/links", s.token(), Map.of("type", "URL", "destinationUrl", "https://example.com/offer", "code", "diwali", "utmSource", "whatsapp", "utmCampaign", "diwali"));
        assertThat(custom.status()).isEqualTo(200);
        assertThat(api.post("/api/v1/links", s.token(), Map.of("type", "URL", "destinationUrl", "https://example.com/x", "code", "diwali")).status()).isEqualTo(409); // taken
        assertThat(api.post("/api/v1/links", s.token(), Map.of("type", "URL", "destinationUrl", "https://bit.ly/abc")).status()).isEqualTo(400); // shortener
        assertThat(api.post("/api/v1/links", s.token(), Map.of("type", "URL", "destinationUrl", "http://example.com")).status()).isEqualTo(400); // https only
        var d = mvc.perform(MockMvcRequestBuilders.get("/l/diwali").header("User-Agent", "Mozilla/5.0 (Windows NT 10.0)")).andReturn().getResponse();
        assertThat(d.getHeader("Location")).isEqualTo("https://example.com/offer?utm_source=whatsapp&utm_campaign=diwali");
        long lid = custom.body().get("id").asLong();
        api.patch("/api/v1/links/" + lid, s.token(), Map.of("active", false));
        var ended = mvc.perform(MockMvcRequestBuilders.get("/l/diwali").header("User-Agent", "Mozilla/5.0 (Windows NT 10.0)")).andReturn().getResponse();
        assertThat(ended.getStatus()).isEqualTo(410);
        assertThat(ended.getContentAsString()).contains("This link has ended");
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/l/nope123").header("User-Agent", "x Windows")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/api/v1/links/" + id + "/qr.png").header("Authorization", "Bearer " + s.token())).andReturn().getResponse().getContentAsByteArray()).hasSizeGreaterThan(500);
    }

    @Test
    void domainsPlanLimitsAndTlsAsk() throws Exception {
        Api.Session s = api.signup(); // GROWTH trial: 2 domains
        var bad = api.post("/api/v1/domains", s.token(), Map.of("domain", "Not A Domain", "purpose", "LINKS"));
        assertThat(bad.status()).isEqualTo(400);
        var d1 = api.post("/api/v1/domains", s.token(), Map.of("domain", "offers.myshop.in", "purpose", "LANDING_PAGES"));
        assertThat(d1.status()).isEqualTo(200);
        assertThat(d1.body().at("/instructions/routing/type").asText()).isEqualTo("CNAME");
        assertThat(d1.body().at("/instructions/txt/host").asText()).isEqualTo("_mp-verify.offers.myshop.in");
        var apex = api.post("/api/v1/domains", s.token(), Map.of("domain", "myshop.in", "purpose", "LINKS"));
        assertThat(apex.body().at("/instructions/routing/type").asText()).isEqualTo("A");
        assertThat(api.post("/api/v1/domains", s.token(), Map.of("domain", "third.myshop.in", "purpose", "LINKS")).status()).isEqualTo(403); // plan limit of 2
        assertThat(api.post("/api/v1/domains", api.signup().token(), Map.of("domain", "offers.myshop.in", "purpose", "LINKS")).status()).isEqualTo(409); // unique across workspaces

        var ask1 = mvc.perform(MockMvcRequestBuilders.get("/internal/tls/ask?domain=offers.myshop.in").with(r -> { r.setRemoteAddr("127.0.0.1"); return r; })).andReturn().getResponse();
        assertThat(ask1.getStatus()).isEqualTo(404); // not verified yet
        api.post("/api/v1/domains/" + d1.body().get("id").asLong() + "/verify", s.token(), null); // SKIP_DNS in tests
        var ask2 = mvc.perform(MockMvcRequestBuilders.get("/internal/tls/ask?domain=offers.myshop.in").with(r -> { r.setRemoteAddr("127.0.0.1"); return r; })).andReturn().getResponse();
        assertThat(ask2.getStatus()).isEqualTo(200);
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/internal/tls/ask?domain=offers.myshop.in").with(r -> { r.setRemoteAddr("8.8.8.8"); return r; })).andReturn().getResponse().getStatus()).isEqualTo(403);

        // landing page on the verified custom domain
        long domainId = d1.body().get("id").asLong();
        var page = api.post("/api/v1/pages", s.token(), Map.of("title", "Diwali Offer", "path", "diwali", "domainId", domainId,
                "blocks", List.of(Map.of("type", "hero", "heading", "Big Sale", "subheading", "Everything 20% off"), Map.of("type", "text", "html", "<p>Hello <script>alert(1)</script><b>there</b></p>"),
                        Map.of("type", "whatsapp", "phone", "9876500777", "text", "Hi"))));
        assertThat(page.status()).isEqualTo(200);
        long pid = page.body().get("id").asLong();
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/p/_domain/diwali").header("X-Forwarded-Host", "offers.myshop.in")).andReturn().getResponse().getStatus()).isEqualTo(404); // not published
        assertThat(api.post("/api/v1/pages/" + pid + "/publish", s.token(), null).text("/status")).isEqualTo("PUBLISHED");
        var html = mvc.perform(MockMvcRequestBuilders.get("/p/_domain/diwali").header("X-Forwarded-Host", "offers.myshop.in")).andReturn().getResponse();
        assertThat(html.getStatus()).isEqualTo(200);
        assertThat(html.getContentAsString()).contains("Big Sale").contains("wa.me/919876500777").doesNotContain("<script>alert");
        var noCta = api.post("/api/v1/pages", s.token(), Map.of("title", "No cta", "path", "plain", "blocks", List.of(Map.of("type", "text", "html", "x"))));
        assertThat(api.post("/api/v1/pages/" + noCta.body().get("id").asLong() + "/publish", s.token(), null).status()).isEqualTo(400);
    }

    @Test
    void automationValidationAndKeywordFlow() throws Exception {
        Api.Session s = api.signup();
        connectWhatsApp(s);
        long tpl = approvedTemplate(s, "welcome_msg", "UTILITY", "Hi {{1}}, thanks for contacting us. We will reply shortly.", List.of("Ravi"));
        topUp(s.workspaceId(), "10");
        Map<String, Object> step = Map.of("type", "SEND_TEMPLATE", "templateId", tpl, "variables", Map.of("1", Map.of("type", "field", "value", "firstName")));

        assertThat(api.post("/api/v1/automations", s.token(), Map.of("name", "Loop", "triggerType", "KEYWORD", "triggerConfig", Map.of("keyword", "STOP"), "steps", List.of(step), "allowReentryDays", 30)).status()).isEqualTo(400); // reserved keyword
        assertThat(api.post("/api/v1/automations", s.token(), Map.of("name", "Backwards", "triggerType", "CONTACT_CREATED", "triggerConfig", Map.of(),
                "steps", List.of(step, Map.of("type", "IF", "condition", "REPLIED", "elseGoto", 0), step), "allowReentryDays", 30)).status()).isEqualTo(400); // loops rejected
        assertThat(api.post("/api/v1/automations", s.token(), Map.of("name", "Empty", "triggerType", "CONTACT_CREATED", "triggerConfig", Map.of(), "steps", List.of(), "allowReentryDays", 30)).status()).isEqualTo(400);

        var created = api.post("/api/v1/automations", s.token(), Map.of("name", "Price reply", "triggerType", "KEYWORD", "triggerConfig", Map.of("keyword", "price"),
                "steps", List.of(step, Map.of("type", "WAIT", "minutes", 60), Map.of("type", "ADD_TAG", "tagId", api.post("/api/v1/tags", s.token(), Map.of("name", "Asked price", "color", "#12A388")).body().get("id").asLong())),
                "allowReentryDays", 30));
        assertThat(created.status()).isEqualTo(200);
        long aid = created.body().get("id").asLong();
        assertThat(api.post("/api/v1/automations", s.token(), Map.of("name", "Price reply 2", "triggerType", "KEYWORD", "triggerConfig", Map.of("keyword", "PRICE"), "steps", List.of(step), "allowReentryDays", 0)).status()).isEqualTo(200);
        assertThat(api.patch("/api/v1/automations/" + aid + "/status", s.token(), Map.of("status", "ACTIVE")).text("/status")).isEqualTo("ACTIVE");
        // second one with the same keyword cannot be activated while the first is active
        long second = db.scalar("select id from automations where workspace_id=:ws and name='Price reply 2'", Long.class, "ws", s.workspaceId());
        assertThat(api.patch("/api/v1/automations/" + second + "/status", s.token(), Map.of("status", "ACTIVE")).status()).isEqualTo(400);

        long cid = contact(s, "Keyword Customer", "9876500401", true);
        String phoneNumberId = db.scalar("select phone_number_id from wa_accounts where workspace_id=:ws", String.class, "ws", s.workspaceId());
        String payload = om.writeValueAsString(Map.of("object", "whatsapp_business_account", "entry", List.of(Map.of("id", "x", "changes", List.of(Map.of("field", "messages",
                "value", Map.of("metadata", Map.of("phone_number_id", phoneNumberId), "messages", List.of(Map.of("from", "919876500401", "id", "wamid.kw.1",
                        "timestamp", String.valueOf(System.currentTimeMillis() / 1000), "type", "text", "text", Map.of("body", "Price please"))))))))));
        api.post("/webhooks/meta", null, payload);
        runJobs();
        assertThat(db.count("select count(*) from automation_runs where automation_id=:a", "a", aid)).isEqualTo(1);
        engine.processDue();
        runJobs();
        var runRow = db.row("select * from automation_runs where automation_id=:a", "a", aid).orElseThrow();
        assertThat(runRow.s("status")).isEqualTo("WAITING"); // template sent, now waiting 60 minutes
        assertThat(db.count("select count(*) from messages where direction='OUT' and type='TEMPLATE' and workspace_id=:ws", "ws", s.workspaceId())).isEqualTo(1);
        db.update("update automation_runs set next_run_at=:t where id=:id", "t", com.marketingpanel.common.Time.now().minusMinutes(1), "id", runRow.l("id"));
        engine.processDue();
        assertThat(db.row("select status from automation_runs where id=:id", "id", runRow.l("id")).orElseThrow().s("status")).isEqualTo("COMPLETED");
        assertThat(db.count("select count(*) from contact_tags where contact_id=:c", "c", cid)).isEqualTo(1);
        assertThat(wallet.ledgerDifference(s.workspaceId())).isEqualByComparingTo("0");
    }

    @Test
    void apiKeysScopesAndRateLimit() throws Exception {
        Api.Session s = api.signup();
        var k = api.post("/api/v1/api-keys", s.token(), Map.of("name", "POS system", "scopes", List.of("contacts:write")));
        assertThat(k.status()).isEqualTo(200);
        String key = k.text("/key");
        assertThat(key).startsWith("mp_live_");
        assertThat(api.get("/api/v1/api-keys", s.token()).body().toString()).doesNotContain(key); // only the hash is stored

        var noKey = api.post("/api/v1/integrations/contacts", null, Map.of("fullName", "Walk In", "phone", "9876500501"));
        assertThat(noKey.status()).isEqualTo(401);
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/integrations/contacts").header("X-API-Key", key);
        var ok = api.call(req, null, Map.of("fullName", "Walk In", "phone", "9876500501", "whatsappConsent", true, "optInSource", "QR"));
        assertThat(ok.status()).isEqualTo(200);
        var wrongScope = api.call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/integrations/leads").header("X-API-Key", key), null, Map.of("phone", "9876500501"));
        assertThat(wrongScope.status()).isEqualTo(403);
        int limited = 0;
        for (int i = 0; i < 65; i++) {
            var r = api.call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/integrations/contacts").header("X-API-Key", key), null,
                    Map.of("fullName", "Walk In", "phone", "98765" + String.format("%05d", 600 + i)));
            if (r.status() == 429) { limited++; assertThat(r.raw().getHeader("Retry-After")).isNotNull(); }
        }
        assertThat(limited).isGreaterThan(0);
        api.delete("/api/v1/api-keys/" + k.body().get("id").asLong(), s.token());
        assertThat(api.call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/integrations/contacts").header("X-API-Key", key), null,
                Map.of("fullName", "Late", "phone", "9876500999")).status()).isEqualTo(401);
    }
}
