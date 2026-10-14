package com.marketingpanel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
class AuthFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    Api api;

    @BeforeEach
    void init() {
        api = new Api(mvc, om);
    }

    @Test
    void signupVerifyLoginAndMe() throws Exception {
        Api.Session s = api.signup();
        var me = api.get("/api/v1/auth/me", s.token());
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.body().at("/workspace/status").asText()).isEqualTo("TRIAL");
        assertThat(me.body().at("/workspace/role").asText()).isEqualTo("OWNER");

        var login = api.post("/api/v1/auth/login", null, Map.of("identifier", s.email(), "password", "Str0ng@Pass1"));
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.raw().getHeader("Set-Cookie")).contains("mp_rt=").contains("HttpOnly").contains("SameSite=Strict");

        var bad = api.post("/api/v1/auth/login", null, Map.of("identifier", s.email(), "password", "wrong"));
        assertThat(bad.status()).isEqualTo(401);
        assertThat(bad.text("/code")).isEqualTo("UNAUTHORIZED");
        assertThat(bad.text("/message")).contains("incorrect");
    }

    @Test
    void validationErrorsUseSrsFormat() throws Exception {
        var r = api.post("/api/v1/auth/signup", null, Map.of("fullName", "A", "businessName", "x", "businessType", "RETAIL",
                "phone", "12345", "email", "bad", "password", "weak", "confirmPassword", "weak", "city", "T", "acceptTerms", false,
                "captchaToken", ""));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.text("/code")).isEqualTo("VALIDATION_FAILED");
        assertThat(r.body().at("/fieldErrors").size()).isGreaterThan(4);
        assertThat(r.body().at("/traceId").asText()).isNotEmpty();
        boolean phone = false;
        for (var f : r.body().at("/fieldErrors")) if (f.get("field").asText().equals("phone") && f.get("code").asText().equals("vr.mobile.invalid")) phone = true;
        assertThat(phone).isTrue();
    }

    @Test
    void lockoutAfterFiveFailuresAndRefreshRotation() throws Exception {
        Api.Session s = api.signup();
        for (int i = 0; i < 5; i++) api.post("/api/v1/auth/login", null, Map.of("identifier", s.phone(), "password", "nope"));
        var locked = api.post("/api/v1/auth/login", null, Map.of("identifier", s.phone(), "password", "Str0ng@Pass1"));
        assertThat(locked.status()).isEqualTo(423);
        assertThat(locked.text("/code")).isEqualTo("ACCOUNT_LOCKED");
    }

    @Test
    void protectedEndpointNeedsToken() throws Exception {
        assertThat(api.get("/api/v1/auth/me", null).status()).isEqualTo(401);
    }
}
