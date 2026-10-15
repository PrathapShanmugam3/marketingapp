package com.marketingpanel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CrmTest extends BaseIT {

    @Test
    void contactsCrudValidationDuplicatesAndTenantIsolation() throws Exception {
        Api.Session a = api.signup();
        Api.Session b = api.signup();
        long id = contact(a, "Ravi Kumar", "9876500001", true);

        var dup = api.post("/api/v1/contacts", a.token(), Map.of("fullName", "Other", "phone", "+919876500001"));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.body().at("/existingId").asLong()).isEqualTo(id);

        var bad = api.post("/api/v1/contacts", a.token(), Map.of("fullName", "R", "phone", "123"));
        assertThat(bad.status()).isEqualTo(400);

        var list = api.get("/api/v1/contacts?q=ravi", a.token());
        assertThat(list.body().at("/items").size()).isEqualTo(1);
        assertThat(list.body().at("/items/0/phone").asText()).isEqualTo("+919876500001");
        assertThat(list.body().at("/items/0/optInStatus").asText()).isEqualTo("OPTED_IN");

        assertThat(api.get("/api/v1/contacts?q=r", a.token()).status()).isEqualTo(400); // min 2 chars

        // workspace B cannot read or change A's contact
        assertThat(api.get("/api/v1/contacts/" + id, b.token()).status()).isEqualTo(404);
        assertThat(api.delete("/api/v1/contacts/" + id, b.token()).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/contacts", b.token()).body().at("/items").size()).isEqualTo(0);

        var upd = api.put("/api/v1/contacts/" + id, a.token(), Map.of("fullName", "Ravi K", "phone", "9876500001", "city", "Trichy", "whatsappConsent", true, "optInSource", "MANUAL"));
        assertThat(upd.status()).isEqualTo(200);
        assertThat(upd.text("/city")).isEqualTo("Trichy");

        assertThat(api.delete("/api/v1/contacts/" + id, a.token()).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/contacts/" + id, a.token()).status()).isEqualTo(404);
        // re-adding a soft-deleted number restores it
        assertThat(api.post("/api/v1/contacts", a.token(), Map.of("fullName", "Ravi Again", "phone", "9876500001")).status()).isEqualTo(200);
    }

    @Test
    void tagsSegmentsAndImport() throws Exception {
        Api.Session s = api.signup();
        var tag = api.post("/api/v1/tags", s.token(), Map.of("name", "VIP", "color", "#12A388"));
        assertThat(tag.status()).isEqualTo(200);
        assertThat(api.post("/api/v1/tags", s.token(), Map.of("name", "vip", "color", "#12A388")).status()).isEqualTo(409);

        String csv = "Name,Mobile,City\nAsha R,9876511111,Trichy\nBad,12,Chennai\nMeena S,9876522222,Madurai\nAsha Dup,9876511111,X\n";
        var up = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/contacts/imports")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "c.csv", "text/csv", csv.getBytes()))
                .header("Authorization", "Bearer " + s.token())).andReturn().getResponse();
        assertThat(up.getStatus()).isEqualTo(200);
        var upBody = om.readTree(up.getContentAsString());
        long importId = upBody.get("id").asLong();
        assertThat(upBody.get("totalRows").asInt()).isEqualTo(4);
        assertThat(upBody.at("/suggestedMapping/1").asText()).isEqualTo("phone");

        var mapping = Map.of("0", "fullName", "1", "phone", "2", "city");
        var prev = api.post("/api/v1/contacts/imports/" + importId + "/preview", s.token(), Map.of("mapping", mapping, "defaultCountryCode", "91"));
        assertThat(prev.body().at("/rows/1/error").asText()).contains("mobile");

        assertThat(api.post("/api/v1/contacts/imports/" + importId + "/start", s.token(),
                Map.of("mapping", mapping, "defaultCountryCode", "91", "duplicateMode", "SKIP", "newTagName", "Imported", "consentConfirmed", false)).status()).isEqualTo(400);
        assertThat(api.post("/api/v1/contacts/imports/" + importId + "/start", s.token(),
                Map.of("mapping", mapping, "defaultCountryCode", "91", "duplicateMode", "SKIP", "newTagName", "Imported", "consentConfirmed", true)).status()).isEqualTo(200);
        runJobs();
        var st = api.get("/api/v1/contacts/imports/" + importId, s.token());
        assertThat(st.text("/status")).isEqualTo("COMPLETED");
        assertThat(st.body().at("/importedRows").asInt()).isEqualTo(2);
        assertThat(st.body().at("/failedRows").asInt()).isEqualTo(2);
        assertThat(st.text("/errorReportUrl")).contains("/files/");

        var contacts = api.get("/api/v1/contacts", s.token());
        assertThat(contacts.body().at("/total").asInt()).isEqualTo(2);
        assertThat(contacts.body().at("/items/0/optInStatus").asText()).isEqualTo("OPTED_IN");

        long tagId = db.scalar("select id from tags where workspace_id=:ws and name='Imported'", Long.class, "ws", s.workspaceId());
        assertThat(api.get("/api/v1/contacts?tagIds=" + tagId, s.token()).body().at("/total").asInt()).isEqualTo(2);

        var seg = api.post("/api/v1/segments", s.token(), Map.of("name", "Trichy folks",
                "filter", Map.of("match", "AND", "conditions", List.of(Map.of("field", "city", "op", "eq", "value", "Trichy")))));
        assertThat(seg.status()).isEqualTo(200);
        assertThat(seg.body().at("/contactCount").asInt()).isEqualTo(1);
        assertThat(api.post("/api/v1/segments/preview", s.token(), Map.of("match", "AND", "conditions", List.of(Map.of("field", "bogus", "op", "eq", "value", "x")))).status()).isEqualTo(400);
    }

    @Test
    void leadsFollowUpAndStaffVisibility() throws Exception {
        Api.Session s = api.signup();
        long cid = contact(s, "Lead Person", "9876533333", false);
        var lead = api.post("/api/v1/leads", s.token(), Map.of("contactId", cid, "source", "CALL", "requirement", "Needs 2 sarees"));
        assertThat(lead.status()).isEqualTo(200);
        long id = lead.body().get("id").asLong();
        assertThat(lead.text("/status")).isEqualTo("NEW");

        var lost = api.patch("/api/v1/leads/" + id, s.token(), Map.of("status", "LOST"));
        assertThat(lost.status()).isEqualTo(400); // reason required
        assertThat(api.patch("/api/v1/leads/" + id, s.token(), Map.of("status", "LOST", "lostReason", "Too costly")).status()).isEqualTo(200);
        assertThat(api.patch("/api/v1/leads/" + id, s.token(), Map.of("status", "INTERESTED")).text("/status")).isEqualTo("INTERESTED"); // backward move allowed

        assertThat(api.post("/api/v1/leads/" + id + "/notes", s.token(), Map.of("note", "")).status()).isEqualTo(400);
        var withNote = api.post("/api/v1/leads/" + id + "/notes", s.token(), Map.of("note", "Called, will visit Sunday"));
        assertThat(withNote.body().at("/activities").size()).isGreaterThanOrEqualTo(3);

        var past = api.patch("/api/v1/leads/" + id, s.token(), Map.of("followUpAt", java.time.Instant.now().minusSeconds(60).toString()));
        assertThat(past.status()).isEqualTo(400);
    }

    @Test
    void publicFormCreatesOptedInContactAndLead() throws Exception {
        Api.Session s = api.signup(); // trial gets the GROWTH plan with forms
        var form = api.post("/api/v1/forms", s.token(), Map.of("name", "Enquiry", "slug", "enquiry-" + System.nanoTime() % 100000, "title", "Contact us",
                "fields", List.of(Map.of("key", "message", "label", "Message", "type", "text", "required", false)),
                "consentText", "I agree to be contacted on WhatsApp"));
        assertThat(form.status()).isEqualTo(200);
        String slug = form.text("/slug");
        assertThat(form.body().at("/fieldsJson").size()).isEqualTo(3); // name + phone always included

        var noConsent = api.post("/f/" + slug, null, Map.of("fields", Map.of("name", "Guest One", "phone", "9876544444"), "consent", false, "captchaToken", "x"));
        assertThat(noConsent.status()).isEqualTo(400);
        var ok = api.post("/f/" + slug, null, Map.of("fields", Map.of("name", "Guest One", "phone", "9876544444", "message", "price?"), "consent", true, "captchaToken", "x"));
        assertThat(ok.status()).isEqualTo(200);
        var hp = api.post("/f/" + slug, null, Map.of("fields", Map.of("name", "Bot", "phone", "9876555555"), "consent", true, "website", "spam", "captchaToken", "x"));
        assertThat(hp.status()).isEqualTo(200); // honeypot is silently accepted ...
        assertThat(api.get("/api/v1/contacts?q=Bot", s.token()).body().at("/total").asInt()).isEqualTo(0); // ... but stores nothing

        var c = api.get("/api/v1/contacts?q=Guest", s.token());
        assertThat(c.body().at("/items/0/optInStatus").asText()).isEqualTo("OPTED_IN");
        assertThat(c.body().at("/items/0/optInSource").asText()).isEqualTo("FORM");
        // same mobile again within 24 h updates the same lead
        api.post("/f/" + slug, null, Map.of("fields", Map.of("name", "Guest One", "phone", "9876544444"), "consent", true, "captchaToken", "x"));
        assertThat(api.get("/api/v1/leads", s.token()).body().at("/items").size()).isEqualTo(1);
        var page = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/f/" + slug)).andReturn().getResponse();
        assertThat(page.getContentAsString()).contains("Contact us").contains("I agree to be contacted");
        assertThat(page.getHeader("X-Frame-Options")).isNull();
    }
}
