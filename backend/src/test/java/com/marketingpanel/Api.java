package com.marketingpanel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Small MockMvc wrapper for integration tests. */
public class Api {
    private static final AtomicInteger SEQ = new AtomicInteger(1000);
    public final MockMvc mvc;
    public final ObjectMapper om;

    public Api(MockMvc mvc, ObjectMapper om) {
        this.mvc = mvc;
        this.om = om;
    }

    public record Res(int status, JsonNode body, MockHttpServletResponse raw) {
        public String text(String path) {
            return body.at(path).asText();
        }
    }

    public record Session(String token, long userId, long workspaceId, String phone, String email) {}

    public Res call(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : om.writeValueAsString(body));
        b.with(r -> {
            r.setRemoteAddr("10.1.1." + (SEQ.incrementAndGet() % 250));
            return r;
        });
        MockHttpServletResponse res = mvc.perform(b).andReturn().getResponse();
        String txt = res.getContentAsString();
        JsonNode node = txt.isEmpty() || !res.getContentType().contains("json") ? om.nullNode() : om.readTree(txt);
        return new Res(res.getStatus(), node, res);
    }

    public Res get(String url, String token) throws Exception {
        return call(MockMvcRequestBuilders.get(url), token, null);
    }

    public Res post(String url, String token, Object body) throws Exception {
        return call(MockMvcRequestBuilders.post(url), token, body);
    }

    public Res put(String url, String token, Object body) throws Exception {
        return call(MockMvcRequestBuilders.put(url), token, body);
    }

    public Res patch(String url, String token, Object body) throws Exception {
        return call(MockMvcRequestBuilders.patch(url), token, body);
    }

    public Res delete(String url, String token) throws Exception {
        return call(MockMvcRequestBuilders.delete(url), token, null);
    }

    /** Full signup + OTP verification; returns a logged-in owner session. */
    public Session signup(String businessType) throws Exception {
        int n = SEQ.incrementAndGet();
        String phone = "+9198765" + String.format("%05d", n);
        String email = "owner" + n + "@example.com";
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("fullName", "Owner Kumar");
        body.put("businessName", "Test Shop " + n);
        body.put("businessType", businessType);
        body.put("phone", phone);
        body.put("email", email);
        body.put("password", "Str0ng@Pass1");
        body.put("confirmPassword", "Str0ng@Pass1");
        body.put("city", "Trichy");
        body.put("acceptTerms", true);
        body.put("captchaToken", "ok");
        Res s = post("/api/v1/auth/signup", null, body);
        if (s.status() != 200) throw new IllegalStateException("signup failed: " + s.body());
        Res v = post("/api/v1/auth/otp/verify", null, Map.of("phone", phone, "otp", s.text("/devOtp"), "signupToken", s.text("/signupToken")));
        if (v.status() != 200) throw new IllegalStateException("verify failed: " + v.body());
        return new Session(v.text("/accessToken"), v.body().at("/user/id").asLong(), v.body().at("/workspace/id").asLong(), phone, email);
    }

    public Session signup() throws Exception {
        return signup("RETAIL");
    }
}
