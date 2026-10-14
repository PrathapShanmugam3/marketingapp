package com.marketingpanel;

import com.marketingpanel.social.SocialService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewSocialTest extends BaseIT {
    @Autowired SocialService social;

    @Test
    void reviewBoosterFlowWithoutGating() throws Exception {
        Api.Session s = api.signup();
        connectWhatsApp(s);
        long tpl = approvedTemplate(s, "review_ask", "UTILITY", "Hi {{1}}, thanks for visiting. Please review us here: {{2}} Thank you!", List.of("Ravi", "https://g.page/r/abc"));
        long plain = approvedTemplate(s, "plain_note", "UTILITY", "Hello there, thanks for visiting us today.", List.of());
        long cid = contact(s, "Review Person", "9876500701", true);
        long noOpt = contact(s, "No Optin", "9876500702", false);
        topUp(s.workspaceId(), "5");

        assertThat(api.put("/api/v1/reviews/settings", s.token(), Map.of("googleReviewUrl", "https://example.com/x", "templateId", tpl)).status()).isEqualTo(400); // not a Google host
        assertThat(api.put("/api/v1/reviews/settings", s.token(), Map.of("googleReviewUrl", "https://g.page/r/abc/review", "templateId", plain)).status()).isEqualTo(400); // template has no link variable
        assertThat(api.put("/api/v1/reviews/settings", s.token(), Map.of("googleReviewUrl", "https://g.page/r/abc/review", "templateId", tpl, "autoSend", true, "delayMinutes", 5)).status()).isEqualTo(400); // 15 min minimum + tag needed
        assertThat(api.put("/api/v1/reviews/settings", s.token(), Map.of("googleReviewUrl", "https://g.page/r/abc/review", "templateId", tpl)).status()).isEqualTo(200);

        assertThat(api.post("/api/v1/reviews/requests", s.token(), Map.of("contactId", noOpt)).status()).isEqualTo(400); // not opted in
        var req = api.post("/api/v1/reviews/requests", s.token(), Map.of("contactId", cid));
        assertThat(req.status()).isEqualTo(200);
        runJobs();
        assertThat(db.count("select count(*) from messages where direction='OUT' and type='TEMPLATE' and workspace_id=:ws", "ws", s.workspaceId())).isEqualTo(1);
        assertThat(api.post("/api/v1/reviews/requests", s.token(), Map.of("contactId", cid)).status()).isEqualTo(400); // once per 30 days

        String link = req.text("/link");
        var go = mvc.perform(MockMvcRequestBuilders.get(link.substring(link.indexOf("/r/")))).andReturn().getResponse();
        assertThat(go.getStatus()).isEqualTo(302);
        assertThat(go.getHeader("Location")).isEqualTo("https://g.page/r/abc/review"); // same link for everyone: no gating
        var stats = api.get("/api/v1/reviews/stats", s.token());
        assertThat(stats.body().get("requestsSent").asInt()).isEqualTo(1);
        assertThat(stats.body().get("clicked").asInt()).isEqualTo(1);
        assertThat(api.post("/api/v1/reviews/stats", s.token(), Map.of("rating", 4.6, "reviewCount", 120)).body().at("/latest/rating").asDouble()).isEqualTo(4.6);
        assertThat(api.post("/api/v1/reviews/stats", s.token(), Map.of("rating", 9, "reviewCount", 1)).status()).isEqualTo(400);

        var pdf = mvc.perform(MockMvcRequestBuilders.get("/api/v1/reviews/poster.pdf?size=A5").header("Authorization", "Bearer " + s.token())).andReturn().getResponse();
        assertThat(new String(pdf.getContentAsByteArray(), 0, 4)).isEqualTo("%PDF");
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/api/v1/reviews/qr.png").header("Authorization", "Bearer " + s.token())).andReturn().getResponse().getContentAsByteArray()).hasSizeGreaterThan(500);
    }

    @Test
    void socialPostsScheduleCancelPublishAndRetry() throws Exception {
        Api.Session s = api.signup();
        var acc = api.post("/api/v1/social/accounts", s.token(), Map.of("platform", "INSTAGRAM", "externalId", "ig123", "name", "My Shop IG", "accessToken", "tok"));
        assertThat(acc.status()).isEqualTo(200);
        long ig = acc.body().at("/accounts/0/id").asLong();
        var fb = api.post("/api/v1/social/accounts", s.token(), Map.of("platform", "FACEBOOK", "externalId", "fb123", "name", "My Shop Page", "accessToken", "tok"));
        long fbId = fb.body().at("/accounts/1/id").asLong();
        String media = "https://cdn.example.com/p1.jpg";

        assertThat(api.post("/api/v1/social/posts", s.token(), Map.of("accountIds", List.of(), "postType", "IMAGE", "media", List.of(media), "caption", "Hi")).status()).isEqualTo(400);
        assertThat(api.post("/api/v1/social/posts", s.token(), Map.of("accountIds", List.of(ig), "postType", "TEXT", "caption", "text only")).status()).isEqualTo(400); // IG needs media
        assertThat(api.post("/api/v1/social/posts", s.token(), Map.of("accountIds", List.of(ig), "postType", "IMAGE", "media", List.of(media), "caption", "x".repeat(2300))).status()).isEqualTo(400);
        assertThat(api.post("/api/v1/social/posts", s.token(), Map.of("accountIds", List.of(ig), "postType", "IMAGE", "media", List.of(media), "caption", "Hi", "scheduledAt", Instant.now().plusSeconds(60).toString())).status()).isEqualTo(400); // < 10 min

        var now = api.post("/api/v1/social/posts", s.token(), Map.of("accountIds", List.of(ig, fbId), "postType", "IMAGE", "media", List.of(media), "caption", "New arrivals #saree"));
        assertThat(now.status()).isEqualTo(200);
        runJobs();
        var done = api.get("/api/v1/social/posts/" + now.body().get("id").asLong(), s.token());
        assertThat(done.text("/status")).isEqualTo("PUBLISHED");
        assertThat(done.body().at("/targets").size()).isEqualTo(2);

        var sched = api.post("/api/v1/social/posts", s.token(), Map.of("accountIds", List.of(fbId), "postType", "TEXT", "caption", "Tomorrow sale", "scheduledAt", Instant.now().plusSeconds(86400).toString()));
        assertThat(sched.text("/status")).isEqualTo("SCHEDULED");
        assertThat(api.post("/api/v1/social/posts/" + sched.body().get("id").asLong() + "/cancel", s.token(), null).text("/status")).isEqualTo("CANCELLED");

        // failing publish retries 3 times (1, 5, 15 min), then fails and notifies the creator
        var bad = api.post("/api/v1/social/posts", s.token(), Map.of("accountIds", List.of(fbId), "postType", "TEXT", "caption", "FAILPUBLISH now"));
        long badId = bad.body().get("id").asLong();
        for (int i = 0; i < 6; i++) {
            db.update("update social_post_targets set next_retry_at=null where post_id=:p", "p", badId);
            runJobs();
        }
        var failed = api.get("/api/v1/social/posts/" + badId, s.token());
        assertThat(failed.text("/status")).isEqualTo("FAILED");
        assertThat(failed.body().at("/targets/0/attempts").asInt()).isEqualTo(4);
        assertThat(db.exists("select 1 from notifications where workspace_id=:ws and type='SOCIAL_FAILED'", "ws", s.workspaceId())).isTrue();

        // an expired token blocks scheduling and asks the Owner to reconnect
        db.update("update social_accounts set token_expires_at = date_sub(utc_timestamp(3), interval 1 day) where id=:id", "id", ig);
        var exp = api.post("/api/v1/social/posts", s.token(), Map.of("accountIds", List.of(ig), "postType", "IMAGE", "media", List.of(media), "caption", "late"));
        assertThat(exp.status()).isEqualTo(409);
        assertThat(exp.text("/message")).contains("reconnect");
    }

    @Test
    void aiCaptionsLimitsAndPlanGate() throws Exception {
        Api.Session s = api.signup();
        var tooShort = api.post("/api/v1/ai/generate", s.token(), Map.of("topic", "sale", "language", "en", "tone", "Friendly"));
        assertThat(tooShort.status()).isEqualTo(400);
        var en = api.post("/api/v1/ai/generate", s.token(), Map.of("type", "CAPTION", "topic", "Diwali sale on silk sarees, 20% off", "language", "en", "tone", "Festive"));
        assertThat(en.status()).isEqualTo(200);
        assertThat(en.text("/output")).contains("Diwali sale");
        var ta = api.post("/api/v1/ai/generate", s.token(), Map.of("type", "OFFER_MESSAGE", "topic", "தீபாவளி சிறப்பு தள்ளுபடி", "language", "ta", "tone", "Friendly"));
        assertThat(ta.text("/output")).contains("{{1}}");
        var usage = api.get("/api/v1/ai/usage", s.token());
        assertThat(usage.body().get("used").asInt()).isEqualTo(2);
        assertThat(usage.body().get("limit").asInt()).isEqualTo(200);
        db.update("update ai_generations set created_at=created_at where workspace_id=:ws", "ws", s.workspaceId());
        for (int i = 0; i < 198; i++) db.insertInto("ai_generations", "workspace_id", s.workspaceId(), "type", "CAPTION", "language", "en", "prompt", "p", "output", "o");
        var limit = api.post("/api/v1/ai/generate", s.token(), Map.of("topic", "One more caption please", "language", "en", "tone", "Friendly"));
        assertThat(limit.status()).isEqualTo(403);
        assertThat(limit.text("/code")).isEqualTo("PLAN_LIMIT_REACHED");
    }

    @Test
    void notificationsAndTeamRoles() throws Exception {
        Api.Session s = api.signup();
        var invite = api.post("/api/v1/team", s.token(), Map.of("email", "mgr@example.com", "role", "MANAGER"));
        assertThat(invite.status()).isEqualTo(200);
        assertThat(api.post("/api/v1/team", s.token(), Map.of("email", "mgr@example.com", "role", "STAFF")).status()).isEqualTo(409);
        var team = api.get("/api/v1/team", s.token());
        assertThat(team.body().at("/members").size()).isEqualTo(2);
        // the owner cannot remove or demote themselves as the last owner
        long ownerMember = team.body().at("/members/0/id").asLong();
        assertThat(api.delete("/api/v1/team/" + ownerMember, s.token()).status()).isEqualTo(409);
        assertThat(api.patch("/api/v1/team/" + ownerMember, s.token(), Map.of("role", "MANAGER")).status()).isEqualTo(409);

        // invitee accepts and gets staff-level access only
        String token = db.scalar("select invite_email from workspace_members where workspace_id=:ws and invite_status='PENDING'", String.class, "ws", s.workspaceId());
        assertThat(token).isEqualTo("mgr@example.com");
        db.update("update workspace_members set invite_token_hash=:h where workspace_id=:ws and invite_status='PENDING'", "h", com.marketingpanel.common.Hashing.sha256("known-token"), "ws", s.workspaceId());
        var acc = api.post("/api/v1/auth/invite/accept", null, Map.of("token", "known-token", "fullName", "Mani Manager", "phone", "9876500990", "password", "Str0ng@Pass1"));
        assertThat(acc.status()).isEqualTo(200);
        String mgr = acc.text("/accessToken");
        assertThat(api.get("/api/v1/team", mgr).status()).isEqualTo(403); // manager cannot manage team
        assertThat(api.get("/api/v1/billing", mgr).status()).isEqualTo(403); // nor billing
        assertThat(api.get("/api/v1/contacts", mgr).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/templates", mgr, Map.of("name", "x_y_z", "category", "UTILITY", "language", "en", "body", "Hello")).status()).isIn(409, 400); // allowed role; fails on business rule, not 403
        assertThat(api.put("/api/v1/workspace", mgr, Map.of("name", "Hacked Shop", "phone", "9876500990", "city", "Trichy", "state", "Tamil Nadu", "language", "en")).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/notifications", s.token()).status()).isEqualTo(200);
    }
}
