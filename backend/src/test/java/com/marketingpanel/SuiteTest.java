package com.marketingpanel;

import com.marketingpanel.engage.Unsubscribe;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SuiteTest extends BaseIT {
    @Autowired Unsubscribe unsub;

    private long contactWithEmail(Api.Session s, String name, String phone, String email) throws Exception {
        long id = contact(s, name, phone, true);
        var r = api.put("/api/v1/contacts/" + id, s.token(), Map.of("fullName", name, "phone", phone, "email", email, "whatsappConsent", true, "optInSource", "MANUAL"));
        assertThat(r.status()).isEqualTo(200);
        return id;
    }

    @Test
    void emailBroadcastSendsToConsentedContactsAndHonoursUnsubscribe() throws Exception {
        Api.Session s = api.signup();
        long a = contactWithEmail(s, "Asha Rao", "9876500401", "asha@example.com");
        long b = contactWithEmail(s, "Bala Raj", "9876500402", "bala@example.com");
        contact(s, "No Email", "9876500403", true);
        var bc = api.post("/api/v1/broadcasts", s.token(), Map.of("channel", "EMAIL", "name", "Diwali mail", "subject", "Diwali offers inside", "body", "<p>Hi {{first_name}}, 20% off this week.</p><script>alert(1)</script>",
                "audienceType", "ALL_OPTED_IN"));
        assertThat(bc.status()).isEqualTo(200);
        assertThat(bc.text("/body")).doesNotContain("script");
        long id = bc.body().get("id").asLong();
        assertThat(api.get("/api/v1/broadcasts/" + id, s.token()).body().at("/estimate/recipients").asInt()).isEqualTo(2); // contact without e-mail is excluded
        assertThat(api.post("/api/v1/broadcasts/" + id + "/send", s.token(), Map.of()).status()).isEqualTo(200);
        runJobs();
        var done = api.get("/api/v1/broadcasts/" + id, s.token());
        assertThat(done.text("/status")).isEqualTo("COMPLETED");
        assertThat(done.body().get("sentCount").asInt()).isEqualTo(2);

        // unsubscribe link works once and removes the contact from the next send
        String link = unsub.link(s.workspaceId(), a, id, "EMAIL");
        var page = mvc.perform(MockMvcRequestBuilders.get(link.substring(link.indexOf("/u/")))).andReturn().getResponse();
        assertThat(page.getStatus()).isEqualTo(200);
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/u/tampered.token")).andReturn().getResponse().getStatus()).isEqualTo(400);
        var second = api.post("/api/v1/broadcasts", s.token(), Map.of("channel", "EMAIL", "name", "Second", "subject", "Another mail", "body", "<p>More news for you</p>", "audienceType", "ALL_OPTED_IN"));
        assertThat(api.get("/api/v1/broadcasts/" + second.body().get("id").asLong(), s.token()).body().at("/estimate/recipients").asInt()).isEqualTo(1);
        assertThat(db.count("select unsub_count from broadcasts where id=:id", "id", id)).isEqualTo(1);
        // plan feature gate and validation
        assertThat(api.post("/api/v1/broadcasts", s.token(), Map.of("channel", "EMAIL", "name", "x", "subject", "s", "body", "short", "audienceType", "ALL_OPTED_IN")).status()).isEqualTo(400);
        db.update("update plans set features=json_set(features,'$.email',false) where code='GROWTH'");
        try {
            assertThat(api.post("/api/v1/broadcasts", s.token(), Map.of("channel", "EMAIL", "name", "Gated mail", "subject", "Gated mail", "body", "<p>Hello there friend</p>", "audienceType", "ALL_OPTED_IN")).status()).isIn(402, 403);
        } finally {
            db.update("update plans set features=json_set(features,'$.email',true) where code='GROWTH'");
        }
    }

    @Test
    void smsBroadcastChargesPerSegmentAndRefundsUndelivered() throws Exception {
        Api.Session s = api.signup();
        contact(s, "Sms One", "9876500411", true);
        contact(s, "Sms Two", "9876500412", true);
        var bc = api.post("/api/v1/broadcasts", s.token(), Map.of("channel", "SMS", "name", "Sale sms", "body", "Hi {{first_name}}, sale on till Sunday at our shop.", "audienceType", "ALL_OPTED_IN"));
        assertThat(bc.status()).isEqualTo(200);
        long id = bc.body().get("id").asLong();
        var est = api.post("/api/v1/broadcasts/" + id + "/estimate", s.token(), Map.of());
        assertThat(est.body().get("recipients").asInt()).isEqualTo(2);
        assertThat(est.body().get("sufficient").asBoolean()).isFalse(); // empty wallet
        assertThat(api.post("/api/v1/broadcasts/" + id + "/send", s.token(), Map.of()).status()).isEqualTo(402);
        topUp(s.workspaceId(), "100");
        assertThat(api.post("/api/v1/broadcasts/" + id + "/send", s.token(), Map.of()).status()).isEqualTo(200);
        runJobs();
        var done = api.get("/api/v1/broadcasts/" + id, s.token());
        assertThat(done.text("/status")).isEqualTo("COMPLETED");
        assertThat(wallet.balance(s.workspaceId())).isEqualByComparingTo("99.50"); // 2 x 0.25 spent
        assertThat(wallet.ledgerDifference(s.workspaceId())).isEqualByComparingTo("0");
        assertThat(com.marketingpanel.engage.BroadcastService.smsSegments("a".repeat(161))).isEqualTo(2);
        assertThat(com.marketingpanel.engage.BroadcastService.smsSegments("₹".repeat(71))).isEqualTo(2);
    }

    @Test
    void chatbotAnswersKeywordsOncePerWindowAndNeverStopWords() throws Exception {
        Api.Session s = api.signup();
        connectWhatsApp(s);
        long cid = contact(s, "Chat Person", "9876500421", true);
        String phoneNumberId = db.scalar("select phone_number_id from wa_accounts where workspace_id=:ws", String.class, "ws", s.workspaceId());
        assertThat(api.post("/api/v1/chatbot/rules", s.token(), Map.of("name", "Hours", "matchType", "CONTAINS", "keywords", List.of("timing", "open"), "replyText", "We are open 9am-9pm every day.")).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/chatbot/rules", s.token(), Map.of("name", "Bad", "matchType", "EXACT", "keywords", List.of("stop"), "replyText", "no")).status()).isEqualTo(400);
        assertThat(api.post("/api/v1/chatbot/test", s.token(), Map.of("text", "What are your timings?", "firstMessage", false)).body().get("matched").asBoolean()).isTrue();
        assertThat(api.post("/api/v1/chatbot/test", s.token(), Map.of("text", "do you deliver", "firstMessage", false)).body().get("matched").asBoolean()).isFalse();

        java.util.function.BiConsumer<String, String> inbound = (id, text) -> {
            try {
                String payload = om.writeValueAsString(Map.of("object", "whatsapp_business_account", "entry", List.of(Map.of("id", "x", "changes", List.of(Map.of("field", "messages",
                        "value", Map.of("metadata", Map.of("phone_number_id", phoneNumberId), "contacts", List.of(Map.of("wa_id", "919876500421", "profile", Map.of("name", "Chat Person"))),
                                "messages", List.of(Map.of("from", "919876500421", "id", id, "timestamp", String.valueOf(System.currentTimeMillis() / 1000), "type", "text", "text", Map.of("body", text))))))))));
                api.post("/webhooks/meta", null, payload);
                runJobs();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        inbound.accept("wamid.bot.1", "What are your timings?");
        assertThat(db.count("select count(*) from messages where workspace_id=:ws and direction='OUT' and sent_by is null and body like 'We are open%'", "ws", s.workspaceId())).isEqualTo(1);
        inbound.accept("wamid.bot.2", "random question");
        assertThat(db.count("select count(*) from messages where workspace_id=:ws and direction='OUT'", "ws", s.workspaceId())).isEqualTo(1); // no rule, no fallback
        assertThat(api.post("/api/v1/chatbot/rules", s.token(), Map.of("name", "Fallback", "matchType", "FALLBACK", "replyText", "A team member will reply soon.", "handoff", true)).status()).isEqualTo(200);
        inbound.accept("wamid.bot.3", "another question");
        inbound.accept("wamid.bot.4", "and one more");
        assertThat(db.count("select count(*) from messages where workspace_id=:ws and direction='OUT' and body like 'A team member%'", "ws", s.workspaceId())).isEqualTo(1); // fallback only once per 6 hours
        assertThat(db.count("select count(*) from notifications where workspace_id=:ws and type='BOT_HANDOFF'", "ws", s.workspaceId())).isGreaterThanOrEqualTo(1);
        inbound.accept("wamid.bot.5", "STOP");
        assertThat(api.get("/api/v1/contacts/" + cid, s.token()).text("/optInStatus")).isEqualTo("OPTED_OUT");
        assertThat(db.count("select count(*) from messages where workspace_id=:ws and direction='OUT'", "ws", s.workspaceId())).isEqualTo(2); // nothing sent for STOP
    }

    @Test
    void couponsAndLoyalty() throws Exception {
        Api.Session s = api.signup();
        long c1 = contact(s, "Loyal One", "9876500431", true);
        long c2 = contact(s, "Loyal Two", "9876500432", true);
        var cp = api.post("/api/v1/coupons", s.token(), Map.of("code", "diwali20", "discountType", "PERCENT", "discountValue", 20, "maxDiscount", 300, "minOrder", 500, "maxRedemptions", 2, "perContactLimit", 1));
        assertThat(cp.status()).isEqualTo(200);
        assertThat(api.post("/api/v1/coupons", s.token(), Map.of("code", "DIWALI20", "discountType", "FLAT", "discountValue", 50)).status()).isEqualTo(409);
        var v = api.post("/api/v1/coupons/validate", s.token(), Map.of("code", "DIWALI20", "orderAmount", 2000, "contactId", c1));
        assertThat(new BigDecimal(v.text("/discount"))).isEqualByComparingTo("300"); // capped
        assertThat(api.post("/api/v1/coupons/validate", s.token(), Map.of("code", "DIWALI20", "orderAmount", 100)).status()).isEqualTo(400); // below minimum
        assertThat(api.post("/api/v1/coupons/redeem", s.token(), Map.of("code", "DIWALI20", "orderAmount", 1000, "contactId", c1)).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/coupons/redeem", s.token(), Map.of("code", "DIWALI20", "orderAmount", 1000, "contactId", c1)).status()).isEqualTo(400); // per-customer limit
        assertThat(api.post("/api/v1/coupons/redeem", s.token(), Map.of("code", "DIWALI20", "orderAmount", 1000, "contactId", c2)).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/coupons/redeem", s.token(), Map.of("code", "DIWALI20", "orderAmount", 1000)).status()).isEqualTo(400); // fully used
        assertThat(api.get("/api/v1/coupons", s.token()).body().at("/stats/redemptions").asInt()).isEqualTo(2);

        var earn = api.post("/api/v1/loyalty/earn", s.token(), Map.of("contactId", c1, "orderAmount", 1999));
        assertThat(earn.body().get("earned").asInt()).isEqualTo(99); // 5 points per 100, floored
        assertThat(api.post("/api/v1/loyalty/redeem", s.token(), Map.of("contactId", c1, "points", 1000)).status()).isEqualTo(409);
        assertThat(new BigDecimal(api.post("/api/v1/loyalty/redeem", s.token(), Map.of("contactId", c1, "points", 40)).text("/discount"))).isEqualByComparingTo("40");
        String code = api.get("/api/v1/loyalty/contacts/" + c1, s.token()).text("/referralCode");
        assertThat(api.post("/api/v1/loyalty/refer", s.token(), Map.of("referralCode", code, "referredContactId", c1)).status()).isEqualTo(400); // self
        assertThat(api.post("/api/v1/loyalty/refer", s.token(), Map.of("referralCode", code, "referredContactId", c2)).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/loyalty/refer", s.token(), Map.of("referralCode", code, "referredContactId", c2)).status()).isEqualTo(409); // once only
        assertThat(api.get("/api/v1/loyalty/contacts/" + c1, s.token()).body().get("points").asInt()).isEqualTo(99 - 40 + 100);
        assertThat(api.get("/api/v1/loyalty/contacts/" + c2, s.token()).body().get("points").asInt()).isEqualTo(50);
        assertThat(api.get("/api/v1/loyalty/members", s.token()).body().at("/members").size()).isEqualTo(2);
    }

    @Test
    void catalogContentAndSeo() throws Exception {
        Api.Session s = api.signup();
        var p = api.post("/api/v1/catalog/products", s.token(), Map.of("name", "Silk Saree", "price", 2499, "compareAtPrice", 2999, "description", "Pure silk"));
        assertThat(p.status()).isEqualTo(200);
        assertThat(api.post("/api/v1/catalog/products", s.token(), Map.of("name", "Bad", "price", 100, "compareAtPrice", 90)).status()).isEqualTo(400);
        var page = mvc.perform(MockMvcRequestBuilders.get("/c/" + s.workspaceId())).andReturn().getResponse();
        assertThat(page.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("Silk Saree").contains("₹2,499");

        String body = "<h2>Why silk</h2><p>" + "Silk sarees are loved across Tamil Nadu for weddings and festivals. ".repeat(30) + "</p><img src=\"https://example.com/a.jpg\">";
        var post = api.post("/api/v1/content", s.token(), Map.of("type", "BLOG", "title", "Silk saree buying guide", "bodyHtml", body, "status", "PUBLISHED", "keywords", "silk saree", "seoDescription", "How to choose a silk saree for weddings and festivals in Tamil Nadu."));
        assertThat(post.status()).isEqualTo(200);
        String slug = post.text("/slug");
        assertThat(slug).isEqualTo("silk-saree-buying-guide");
        assertThat(api.post("/api/v1/content", s.token(), Map.of("type", "BLOG", "title", "Silk saree buying guide", "bodyHtml", body, "status", "DRAFT")).status()).isEqualTo(400); // slug already used
        var html = mvc.perform(MockMvcRequestBuilders.get("/b/" + s.workspaceId() + "/" + slug)).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("<title>Silk saree buying guide</title>").contains("canonical").contains("Why silk");
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/b/" + s.workspaceId() + "/sitemap.xml")).andReturn().getResponse().getContentAsString()).contains(slug);
        var chk = api.get("/api/v1/content/" + post.body().get("id").asLong() + "/seo-check", s.token());
        assertThat(chk.body().get("score").asInt()).isGreaterThan(50);
        var draft = api.post("/api/v1/content", s.token(), Map.of("type", "BLOG", "title", "Hidden draft post", "bodyHtml", body, "status", "DRAFT"));
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/b/" + s.workspaceId() + "/" + draft.text("/slug"))).andReturn().getResponse().getStatus()).isEqualTo(404);
        // SSRF guard on the audit tool
        assertThat(api.post("/api/v1/seo/audit", s.token(), Map.of("url", "https://127.0.0.1/")).status()).isEqualTo(400);
        assertThat(api.post("/api/v1/seo/audit", s.token(), Map.of("url", "http://example.com")).status()).isEqualTo(400);
        var kw = api.post("/api/v1/seo/keywords", s.token(), Map.of("keyword", "Silk Saree Chennai"));
        long kid = kw.body().get("id").asLong();
        assertThat(api.post("/api/v1/seo/keywords/" + kid + "/rank", s.token(), Map.of("position", 14, "day", "2026-01-01")).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/seo/keywords/" + kid + "/rank", s.token(), Map.of("position", 9)).status()).isEqualTo(200);
        var ks = api.get("/api/v1/seo/keywords", s.token());
        assertThat(ks.body().at("/0/position").asInt()).isEqualTo(9);
        assertThat(ks.body().at("/0/previous").asInt()).isEqualTo(14);
    }

    @Test
    void adsMetricsCsvSyncAndAnalytics() throws Exception {
        Api.Session s = api.signup();
        var ad = api.post("/api/v1/ads", s.token(), Map.of("platform", "META", "name", "Diwali leads", "objective", "LEADS", "dailyBudget", 500, "totalBudget", 10000, "status", "ACTIVE"));
        assertThat(ad.status()).isEqualTo(200);
        long id = ad.body().get("id").asLong();
        assertThat(api.post("/api/v1/ads/" + id + "/metrics", s.token(), Map.of("day", "2026-01-05", "spend", 400, "impressions", 10000, "clicks", 200, "conversions", 10, "revenue", 6000)).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/ads/" + id + "/metrics", s.token(), Map.of("day", "2026-01-06", "spend", 100, "impressions", 100, "clicks", 200)).status()).isEqualTo(400); // clicks > impressions
        var g = api.get("/api/v1/ads/" + id, s.token());
        assertThat(g.body().get("ctr").asDouble()).isEqualTo(2.0);
        assertThat(new BigDecimal(g.text("/cpa"))).isEqualByComparingTo("40");
        assertThat(new BigDecimal(g.text("/roas"))).isEqualByComparingTo("15");
        String csv = "day,spend,impressions,clicks,conversions,revenue\n2026-01-07,300,5000,100,5,2500\nbad-date,1,1,1,1,1\n";
        var up = mvc.perform(MockMvcRequestBuilders.multipart("/api/v1/ads/" + id + "/import").file(new org.springframework.mock.web.MockMultipartFile("file", "r.csv", "text/csv", csv.getBytes()))
                .header("Authorization", "Bearer " + s.token())).andReturn().getResponse();
        assertThat(up.getStatus()).isEqualTo(200);
        assertThat(up.getContentAsString()).contains("\"imported\":1");
        assertThat(api.post("/api/v1/ads/" + id + "/sync", s.token(), Map.of()).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/ads/" + id, s.token()).body().at("/daily").size()).isGreaterThan(5);

        var overview = api.get("/api/v1/analytics/overview", s.token());
        assertThat(overview.status()).isEqualTo(200);
        assertThat(overview.body().at("/series").size()).isEqualTo(30);
        assertThat(overview.body().at("/ads/spend").asDouble()).isGreaterThan(0);
        assertThat(api.get("/api/v1/analytics/overview?from=2026-03-01&to=2026-01-01", s.token()).status()).isEqualTo(400);
    }

    @Test
    void socialDmInboxAndExtraPlatforms() throws Exception {
        Api.Session s = api.signup();
        var acc = api.post("/api/v1/social/accounts", s.token(), Map.of("platform", "INSTAGRAM", "externalId", "ig1001", "name", "Shop IG", "accessToken", "tok"));
        assertThat(acc.status()).isEqualTo(200);
        assertThat(api.post("/api/v1/social/dm/simulate", s.token(), Map.of("platform", "INSTAGRAM", "pageId", "ig1001", "senderId", "u1", "senderName", "Divya", "text", "Price of the red saree?")).status()).isEqualTo(200);
        var threads = api.get("/api/v1/social/dm/threads", s.token());
        assertThat(threads.body().size()).isEqualTo(1);
        assertThat(threads.body().at("/0/unreadCount").asInt()).isEqualTo(1);
        long tid = threads.body().at("/0/id").asLong();
        assertThat(api.post("/api/v1/social/dm/threads/" + tid + "/messages", s.token(), Map.of("text", "It is Rs 2499, shall I send photos?")).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/social/dm/threads/" + tid + "/messages", s.token(), Map.of("text", " ")).status()).isEqualTo(400);
        db.update("update dm_threads set last_inbound_at = date_sub(utc_timestamp(3), interval 30 hour) where id=:id", "id", tid);
        assertThat(api.post("/api/v1/social/dm/threads/" + tid + "/messages", s.token(), Map.of("text", "Hello?")).status()).isEqualTo(409);
        // Meta webhook payload
        String payload = om.writeValueAsString(Map.of("object", "instagram", "entry", List.of(Map.of("id", "ig1001", "messaging", List.of(Map.of("sender", Map.of("id", "u2"), "message", Map.of("mid", "m1", "text", "Hi")))))));
        assertThat(api.post("/webhooks/social", null, payload).status()).isEqualTo(200);
        assertThat(api.post("/webhooks/social", null, payload).status()).isEqualTo(200); // duplicate ignored
        assertThat(api.get("/api/v1/social/dm/threads", s.token()).body().size()).isEqualTo(2);

        // X, LinkedIn and YouTube accounts and their rules
        for (String p : List.of("X", "LINKEDIN", "YOUTUBE"))
            assertThat(api.post("/api/v1/social/accounts", s.token(), Map.of("platform", p, "externalId", p.toLowerCase() + "1", "name", p + " acct", "accessToken", "tok")).status()).isEqualTo(200);
        List<Long> ids = db.rows("select id from social_accounts where workspace_id=:ws and platform in ('X')", "ws", s.workspaceId()).stream().map(r -> r.l("id")).toList();
        assertThat(api.post("/api/v1/social/posts", s.token(), Map.of("accountIds", ids, "caption", "x".repeat(300), "postType", "TEXT")).status()).isEqualTo(400); // X limit
        assertThat(api.post("/api/v1/social/posts", s.token(), Map.of("accountIds", ids, "caption", "Diwali sale is live!", "postType", "TEXT")).status()).isEqualTo(200);
        List<Long> yt = db.rows("select id from social_accounts where workspace_id=:ws and platform='YOUTUBE'", "ws", s.workspaceId()).stream().map(r -> r.l("id")).toList();
        assertThat(api.post("/api/v1/social/posts", s.token(), Map.of("accountIds", yt, "caption", "Video", "postType", "TEXT")).status()).isEqualTo(400); // needs a video
    }

    @Test
    void pushSubscriptionsAndBroadcast() throws Exception {
        Api.Session s = api.signup();
        var sub = api.post("/api/v1/public/push/" + s.workspaceId() + "/subscribe", null, Map.of("endpoint", "https://push.example.com/send/abc123", "keys", Map.of("p256dh", "k", "auth", "a")));
        assertThat(sub.status()).isEqualTo(200);
        assertThat(api.post("/api/v1/public/push/" + s.workspaceId() + "/subscribe", null, Map.of("endpoint", "http://insecure", "keys", Map.of("p256dh", "k", "auth", "a"))).status()).isEqualTo(400);
        assertThat(api.get("/api/v1/push/stats", s.token()).body().get("subscribers").asInt()).isEqualTo(1);
        var bc = api.post("/api/v1/broadcasts", s.token(), Map.of("channel", "PUSH", "name", "Flash sale", "subject", "Flash sale!", "body", "50% off for 2 hours"));
        assertThat(bc.status()).isEqualTo(200);
        long id = bc.body().get("id").asLong();
        assertThat(api.post("/api/v1/broadcasts/" + id + "/send", s.token(), Map.of()).status()).isEqualTo(200);
        runJobs();
        assertThat(api.get("/api/v1/broadcasts/" + id, s.token()).body().get("sentCount").asInt()).isEqualTo(1);
    }
}
