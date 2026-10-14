package com.marketingpanel.auth;

import com.marketingpanel.common.ApiException;
import com.marketingpanel.common.ClientInfo;
import com.marketingpanel.config.Settings;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    static final String COOKIE = "mp_rt";
    private final AuthService auth;
    private final Settings settings;

    public AuthController(AuthService auth, Settings settings) {
        this.auth = auth;
        this.settings = settings;
    }

    @PostMapping("/signup")
    public Map<String, Object> signup(@Valid @RequestBody AuthDtos.SignupRequest r, HttpServletRequest req) {
        return auth.signup(r, ClientInfo.ip(req));
    }

    public record ResendRequest(@jakarta.validation.constraints.NotBlank String signupToken) {}

    @PostMapping("/signup/resend")
    public Map<String, Object> resend(@Valid @RequestBody ResendRequest r, HttpServletRequest req) {
        return auth.resendSignupOtp(r.signupToken(), ClientInfo.ip(req));
    }

    @GetMapping("/availability")
    public Map<String, Object> availability(@RequestParam(required = false) String phone, @RequestParam(required = false) String email, HttpServletRequest req) {
        return auth.availability(phone, email, ClientInfo.ip(req));
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<AuthDtos.AuthResponse> verify(@Valid @RequestBody AuthDtos.OtpVerifyRequest r, HttpServletRequest req) {
        return withCookie(auth.verifySignup(r, ClientInfo.ip(req), ClientInfo.userAgent()));
    }

    @PostMapping("/otp/send")
    public Map<String, Object> sendOtp(@Valid @RequestBody AuthDtos.OtpSendRequest r, HttpServletRequest req) {
        return auth.sendOtp(r.target(), r.purpose(), ClientInfo.ip(req));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody AuthDtos.LoginRequest r, HttpServletRequest req) {
        Object res = auth.login(r, ClientInfo.ip(req), ClientInfo.userAgent());
        if (res instanceof AuthService.Issued i) return withCookie(i);
        return ResponseEntity.ok(res);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthDtos.AuthResponse> refresh(@RequestBody(required = false) AuthDtos.RefreshRequest body, HttpServletRequest req) {
        String token = cookie(req);
        return withCookie(auth.refresh(token, body == null ? null : body.workspaceId(), ClientInfo.ip(req), ClientInfo.userAgent()));
    }

    @PostMapping("/workspace/select")
    @PreAuthorize(Roles.AUTHENTICATED)
    public ResponseEntity<AuthDtos.AuthResponse> select(@Valid @RequestBody AuthDtos.SelectWorkspaceRequest r, HttpServletRequest req) {
        AuthUser u = Auth.user();
        return withCookie(auth.selectWorkspace(u.userId(), r.workspaceId(), ClientInfo.ip(req), ClientInfo.userAgent(), u.sessionId()));
    }

    @GetMapping("/me")
    @PreAuthorize(Roles.AUTHENTICATED)
    public AuthDtos.AuthResponse me() {
        return auth.me(Auth.user());
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest req) {
        auth.logout(cookie(req));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, clear().toString()).body(Map.of("ok", true));
    }

    @PostMapping("/password/reset")
    public Map<String, Object> reset(@Valid @RequestBody AuthDtos.ResetRequest r, HttpServletRequest req) {
        auth.resetPassword(r, ClientInfo.ip(req));
        return Map.of("ok", true);
    }

    @PostMapping("/invite/accept")
    public ResponseEntity<AuthDtos.AuthResponse> accept(@Valid @RequestBody AuthDtos.InviteAcceptRequest r, HttpServletRequest req) {
        return withCookie(auth.acceptInvite(r, ClientInfo.ip(req), ClientInfo.userAgent()));
    }

    @GetMapping("/sessions")
    @PreAuthorize(Roles.AUTHENTICATED)
    public List<Map<String, Object>> sessions() {
        AuthUser u = Auth.user();
        return auth.sessions(u.userId(), u.sessionId());
    }

    @DeleteMapping("/sessions/{id}")
    @PreAuthorize(Roles.AUTHENTICATED)
    public Map<String, Object> revoke(@PathVariable long id) {
        auth.revokeSession(Auth.userId(), id);
        return Map.of("ok", true);
    }

    private String cookie(HttpServletRequest req) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) if (COOKIE.equals(c.getName())) return c.getValue();
        return null;
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(COOKIE, value).httpOnly(true).secure(settings.cookieSecure).sameSite("Strict").path("/api/v1/auth");
    }

    private ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseEntity<AuthDtos.AuthResponse> withCookie(AuthService.Issued i) {
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, base(i.refreshToken()).maxAge(i.refreshTtl()).build().toString()).body(i.body());
    }
}
