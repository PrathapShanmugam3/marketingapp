package com.marketingpanel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingpanel.common.Db;
import com.marketingpanel.common.Time;
import com.marketingpanel.common.WalletService;
import com.marketingpanel.jobs.JobWorker;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Map;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
abstract class BaseIT {
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper om;
    @Autowired protected Db db;
    @Autowired protected JobWorker worker;
    @Autowired protected WalletService wallet;
    protected Api api;

    @BeforeEach
    void initApi() {
        api = new Api(mvc, om);
    }

    /** Makes every pending job due and runs the queue until it is empty. */
    protected void runJobs() {
        for (int i = 0; i < 6; i++) {
            db.update("update jobs set run_at=:now where status='PENDING'", "now", Time.now());
            if (worker.runDueNow() == 0) break;
        }
    }

    protected void topUp(long ws, String amount) {
        wallet.credit(ws, new BigDecimal(amount), "MANUAL", null, "test credit", null);
    }

    /** Connects the sandbox WhatsApp number and returns after the account exists. */
    protected void connectWhatsApp(Api.Session s) throws Exception {
        long n = Math.abs(System.nanoTime() % 1_000_000_000L);
        var r = api.post("/api/v1/whatsapp/connect", s.token(), Map.of("code", "abc", "wabaId", "waba" + n, "phoneNumberId", "pn" + n));
        if (r.status() != 200) throw new IllegalStateException(r.body().toString());
    }

    protected long approvedTemplate(Api.Session s, String name, String category, String body, java.util.List<String> samples) throws Exception {
        var t = api.post("/api/v1/templates", s.token(), Map.of("name", name, "category", category, "language", "en", "headerType", "NONE", "body", body,
                "sampleValues", samples, "submit", true));
        if (t.status() != 200) throw new IllegalStateException(t.body().toString());
        runJobs();
        long id = t.body().get("id").asLong();
        var g = api.get("/api/v1/templates/" + id, s.token());
        if (!"APPROVED".equals(g.text("/status"))) throw new IllegalStateException("not approved: " + g.body());
        return id;
    }

    protected long contact(Api.Session s, String name, String phone, boolean consent) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("fullName", name);
        body.put("phone", phone);
        body.put("whatsappConsent", consent);
        if (consent) body.put("optInSource", "MANUAL");
        var r = api.post("/api/v1/contacts", s.token(), body);
        if (r.status() != 200) throw new IllegalStateException(r.body().toString());
        return r.body().get("id").asLong();
    }
}
