package com.marketingpanel.auth;

import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import com.marketingpanel.workspace.States;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class AuthService {
    private static final Set<String> DISPOSABLE = Set.of("mailinator.com", "10minutemail.com", "guerrillamail.com", "tempmail.com",
            "yopmail.com", "trashmail.com", "sharklasers.com", "getnada.com", "throwawaymail.com", "dispostable.com");
    private static final String LOGIN_FAIL = "Email/mobile or password is incorrect.";

    private final Db db;
    private final PasswordEncoder encoder;
    private final OtpService otp;
    private final JwtService jwt;
    private final CaptchaVerifier captcha;
    private final RateLimiter limiter;
    private final Crypto crypto;
    private final Settings settings;
    private final AuditService audit;
    private final Notifier notifier;

    public AuthService(Db db, PasswordEncoder encoder, OtpService otp, JwtService jwt, CaptchaVerifier captcha, RateLimiter limiter,
                       Crypto crypto, Settings settings, AuditService audit, Notifier notifier) {
        this.db = db;
        this.encoder = encoder;
        this.otp = otp;
        this.jwt = jwt;
        this.captcha = captcha;
        this.limiter = limiter;
        this.crypto = crypto;
        this.settings = settings;
        this.audit = audit;
        this.notifier = notifier;
    }

    /** Access + refresh token pair; the refresh token goes into an HttpOnly cookie. */
    public record Issued(AuthDtos.AuthResponse body, String refreshToken, Duration refreshTtl) {}

    // ---------------------------------------------------------------- signup

    public Map<String, Object> signup(AuthDtos.SignupRequest r, String ip) {
        limiter.check("signup:" + ip, 5, Duration.ofHours(1));
        List<FieldErr> errs = new ArrayList<>();
        if (!r.password().equals(r.confirmPassword()))
            errs.add(new FieldErr("confirmPassword", "password.mismatch", "Passwords do not match."));
        if (r.password().toLowerCase().contains(r.email().toLowerCase()) || r.password().contains(r.phone().replace("+", "")))
            errs.add(new FieldErr("password", "vr.password.weak", "Use 8+ characters with upper, lower, number and symbol."));
        String domain = r.email().substring(r.email().indexOf('@') + 1);
        if (DISPOSABLE.contains(domain)) errs.add(new FieldErr("email", "email.disposable", "Use a permanent email address."));
        if (r.state() != null && !States.valid(r.state())) errs.add(new FieldErr("state", "state.invalid", "Choose your state."));
        if (db.exists("select 1 from users where phone=:p", "p", r.phone()))
            errs.add(new FieldErr("phone", "phone.exists", "This number is already registered. Log in instead."));
        if (db.exists("select 1 from users where email=:e", "e", r.email()))
            errs.add(new FieldErr("email", "email.exists", "This email is already registered."));
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        captcha.verify(r.captchaToken(), ip, "captchaToken");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fullName", r.fullName());
        payload.put("businessName", r.businessName());
        payload.put("businessType", r.businessType());
        payload.put("phone", r.phone());
        payload.put("email", r.email());
        payload.put("passwordHash", encoder.encode(r.password()));
        payload.put("city", r.city());
        payload.put("state", r.state() == null ? settings.companyState : r.state());
        payload.put("exp", System.currentTimeMillis() + 15 * 60_000L);
        String token = crypto.seal(Json.write(payload));

        OtpService.Sent sent = otp.send(r.phone(), "SIGNUP");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("signupToken", token);
        out.put("otpSentTo", Phones.mask(r.phone()));
        out.put("resendAfterSeconds", sent.resendAfterSeconds());
        if (sent.devOtp() != null) out.put("devOtp", sent.devOtp());
        return out;
    }

    /** Signup OTP verified: account, workspace and owner membership are created in one transaction. */
    public Issued verifySignup(AuthDtos.OtpVerifyRequest r, String ip, String ua) {
        limiter.check("otpverify:" + ip, 20, Duration.ofMinutes(15));
        Map<String, Object> p;
        try {
            p = Json.map(crypto.unseal(r.signupToken()));
        } catch (Exception e) {
            throw ApiException.badRequest("Your sign-up session expired. Please start again.");
        }
        if (((Number) p.get("exp")).longValue() < System.currentTimeMillis())
            throw ApiException.badRequest("Your sign-up session expired. Please start again.");
        if (!r.phone().equals(p.get("phone"))) throw ApiException.badRequest("Your sign-up session does not match this number.");
        otp.verify(r.phone(), "SIGNUP", r.otp());

        long[] ids = db.tx(() -> {
            if (db.exists("select 1 from users where phone=:p or email=:e", "p", p.get("phone"), "e", p.get("email")))
                throw ApiException.duplicate("This number or email is already registered.");
            long userId = db.insertInto("users", "full_name", p.get("fullName"), "email", p.get("email"), "phone", p.get("phone"),
                    "password_hash", p.get("passwordHash"), "phone_verified_at", Time.now(), "account_type", "BUSINESS");
            Long planId = db.scalar("select id from plans where code=:c", Long.class, "c", settings.trialPlan);
            long wsId = db.insertInto("workspaces", "name", p.get("businessName"), "business_type", p.get("businessType"),
                    "phone", p.get("phone"), "email", p.get("email"), "city", p.get("city"), "state", p.get("state"),
                    "plan_id", planId, "status", "TRIAL", "trial_ends_at", Time.now().plusDays(settings.trialDays));
            String role = "AGENCY".equals(p.get("businessType")) ? "AGENCY_ADMIN" : "OWNER";
            db.insertInto("workspace_members", "workspace_id", wsId, "user_id", userId, "role", role, "invite_status", "ACCEPTED",
                    "joined_at", Time.now());
            audit.log(wsId, userId, "SIGNUP", "workspaces", wsId, null, Map.of("plan", settings.trialPlan));
            return new long[]{userId, wsId};
        });
        Row user = db.row("select * from users where id=:id", "id", ids[0]).orElseThrow();
        return issue(user, ids[1], true, ip, ua);
    }

    // ----------------------------------------------------------------- login

    public Map<String, Object> sendOtp(String target, String purpose, String ip) {
        limiter.check("otpsend:" + ip, 10, Duration.ofMinutes(15));
        if (!Set.of("LOGIN", "RESET_PASSWORD").contains(purpose)) throw ApiException.badRequest("Unsupported purpose.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", "If an account exists, we've sent a code.");
        Optional<Row> u = findUser(target);
        if (u.isPresent() && !"DISABLED".equals(u.get().s("status"))) {
            try {
                OtpService.Sent s = otp.send(u.get().s("phone"), purpose);
                if (s.devOtp() != null) out.put("devOtp", s.devOtp());
            } catch (ApiException e) {
                if (e.code() == ErrorCode.RATE_LIMITED) throw e;
            }
        }
        return out;
    }

    public Optional<Row> findUser(String identifier) {
        if (identifier == null || identifier.isBlank()) return Optional.empty();
        String id = identifier.trim();
        if (id.contains("@")) return db.row("select * from users where email=:e", "e", id.toLowerCase());
        String ph = Phones.e164(id);
        return ph == null ? Optional.empty() : db.row("select * from users where phone=:p", "p", ph);
    }

    /** Returns null with otpRequired when admin login still needs its OTP. */
    public Object login(AuthDtos.LoginRequest r, String ip, String ua) {
        limiter.check("login:" + ip, 30, Duration.ofMinutes(15));
        Optional<Row> found = findUser(r.identifier());
        boolean otpMode = r.password() == null || r.password().isEmpty();
        if (found.isEmpty()) {
            encoder.matches("x", "$2a$12$abcdefghijklmnopqrstuuMq0wEbj3A1K9vC0o7B0q9m2pJ8eV3ZK"); // keep timing similar
            throw ApiException.unauthorized(LOGIN_FAIL);
        }
        Row u = found.get();
        limiter.check("login-acct:" + u.l("id"), 15, Duration.ofMinutes(15));
        LocalDateTime now = Time.now();
        if ("DISABLED".equals(u.s("status"))) throw ApiException.forbidden("Your account is disabled. Contact support.");
        if (u.t("locked_until") != null && u.t("locked_until").isAfter(now)) {
            throw new ApiException(ErrorCode.ACCOUNT_LOCKED, "Too many failed tries. Try again at " + Time.fmt(u.t("locked_until")) + ".",
                    List.of(), Map.of("unlockAt", u.t("locked_until").toString() + "Z"), null);
        }
        if (isAdmin(u)) assertAdminIp(ip);

        if (otpMode) {
            if (r.otp() == null || r.otp().isEmpty()) throw ApiException.invalid("otp", "otp.required", "Enter the 6-digit code.");
            verifyOtpForLogin(u, r.otp());
        } else {
            if (!encoder.matches(r.password(), u.s("password_hash"))) {
                failLogin(u);
                throw ApiException.unauthorized(LOGIN_FAIL);
            }
            if (isAdmin(u)) { // staff accounts need an OTP on every login
                if (r.otp() == null || r.otp().isEmpty()) {
                    OtpService.Sent s = otp.send(u.s("phone"), "LOGIN");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("otpRequired", true);
                    m.put("otpSentTo", Phones.mask(u.s("phone")));
                    if (s.devOtp() != null) m.put("devOtp", s.devOtp());
                    return m;
                }
                verifyOtpForLogin(u, r.otp());
            }
        }
        db.update("update users set failed_login_count=0, locked_until=null, last_login_at=:now where id=:id", "now", now, "id", u.l("id"));
        Long ws = defaultWorkspace(u.l("id"));
        return issue(db.row("select * from users where id=:id", "id", u.l("id")).orElseThrow(), ws, r.rememberMe(), ip, ua);
    }

    private static boolean isAdmin(Row u) {
        return u.s("admin_role") != null && !"NONE".equals(u.s("admin_role"));
    }

    private void verifyOtpForLogin(Row u, String code) {
        try {
            otp.verify(u.s("phone"), "LOGIN", code);
        } catch (ApiException e) {
            if (e.code() == ErrorCode.OTP_INVALID) failLogin(u);
            throw e;
        }
    }

    private void failLogin(Row u) {
        int n = u.i("failed_login_count") + 1;
        if (n >= 5) {
            db.update("update users set failed_login_count=0, locked_until=:t where id=:id", "t", Time.now().plusMinutes(15), "id", u.l("id"));
            audit.log(null, u.l("id"), "ACCOUNT_LOCKED", "users", u.l("id"), null, null);
        } else {
            db.update("update users set failed_login_count=:n where id=:id", "n", n, "id", u.l("id"));
        }
    }

    private void assertAdminIp(String ip) {
        if (settings.adminIpAllowlist.isEmpty()) return;
        for (String entry : settings.adminIpAllowlist) if (IpMatcher.matches(entry, ip)) return;
        throw ApiException.forbidden("Admin sign-in is not allowed from this network.");
    }

    // ---------------------------------------------------------- tokens/session

    public Issued issue(Row user, Long workspaceId, boolean remember, String ip, String ua) {
        long uid = user.l("id");
        String role = null;
        if (workspaceId != null) {
            role = db.scalar("select role from workspace_members where workspace_id=:w and user_id=:u and invite_status='ACCEPTED'",
                    String.class, "w", workspaceId, "u", uid);
            if (role == null) workspaceId = null;
        }
        Duration ttl = remember ? Duration.ofDays(30) : Duration.ofDays(1);
        String refresh = Ids.randomToken(32);
        long sid = db.insertInto("refresh_tokens", "user_id", uid, "token_hash", Hashing.sha256(refresh),
                "user_agent", ua, "ip_address", ip, "expires_at", Time.now().plus(ttl));
        AuthUser au = new AuthUser(uid, workspaceId, role, user.s("admin_role"), user.s("account_type"), sid);
        return new Issued(response(user, au), refresh, ttl);
    }

    private AuthDtos.AuthResponse response(Row user, AuthUser au) {
        List<Map<String, Object>> wss = workspacesOf(au.userId());
        Map<String, Object> cur = null;
        if (au.workspaceId() != null) {
            cur = db.one("select w.id, w.name, w.status, w.trial_ends_at, w.wallet_balance, w.state, w.business_type, w.logo_url, w.brand_color, " +
                    "w.language, p.code as plan_code, p.name as plan_name, p.features, p.max_users, p.max_contacts, p.max_domains, p.max_workspaces " +
                    "from workspaces w join plans p on p.id=w.plan_id where w.id=:id", "id", au.workspaceId()).orElse(null);
            if (cur != null) cur.put("role", au.role());
        }
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("id", au.userId());
        u.put("fullName", user.s("full_name"));
        u.put("email", user.s("email"));
        u.put("phone", user.s("phone"));
        u.put("preferredLanguage", user.s("preferred_language"));
        u.put("adminRole", user.s("admin_role"));
        u.put("accountType", user.s("account_type"));
        return new AuthDtos.AuthResponse(jwt.issue(au), JwtService.ACCESS_TTL_SECONDS, u, wss, cur);
    }

    public List<Map<String, Object>> workspacesOf(long userId) {
        return db.list("select w.id, w.name, w.status, m.role, w.parent_workspace_id from workspace_members m " +
                "join workspaces w on w.id=m.workspace_id where m.user_id=:u and m.invite_status='ACCEPTED' and w.deleted_at is null " +
                "order by w.id", "u", userId);
    }

    private Long defaultWorkspace(long userId) {
        return db.scalar("select m.workspace_id from workspace_members m join workspaces w on w.id=m.workspace_id " +
                "where m.user_id=:u and m.invite_status='ACCEPTED' and w.deleted_at is null order by m.workspace_id limit 1", Long.class, "u", userId);
    }

    /** Rotates the refresh token (reuse of a revoked token revokes the whole family). */
    public Issued refresh(String token, Long workspaceId, String ip, String ua) {
        if (token == null || token.isBlank()) throw ApiException.unauthorized("Please log in.");
        Row rt = db.row("select * from refresh_tokens where token_hash=:h", "h", Hashing.sha256(token))
                .orElseThrow(() -> ApiException.unauthorized("Please log in."));
        long uid = rt.l("user_id");
        if (rt.t("revoked_at") != null) {
            db.update("update refresh_tokens set revoked_at=:now where user_id=:u and revoked_at is null", "now", Time.now(), "u", uid);
            audit.log(null, uid, "REFRESH_TOKEN_REUSE", "users", uid, null, null);
            throw ApiException.unauthorized("Your session ended. Please log in again.");
        }
        if (rt.t("expires_at").isBefore(Time.now())) throw ApiException.unauthorized("Your session expired. Please log in again.");
        Row user = db.row("select * from users where id=:id", "id", uid).orElseThrow(() -> ApiException.unauthorized("Please log in."));
        if (!"ACTIVE".equals(user.s("status")) && !"LOCKED".equals(user.s("status")))
            throw ApiException.forbidden("Your account is disabled. Contact support.");
        db.update("update refresh_tokens set revoked_at=:now where id=:id", "now", Time.now(), "id", rt.l("id"));
        long remaining = Duration.between(Time.now(), rt.t("expires_at")).toSeconds();
        Long ws = workspaceId != null ? workspaceId : defaultWorkspace(uid);
        return issue(user, ws, remaining > 2 * 86400, ip, ua);
    }

    public Issued selectWorkspace(long userId, long workspaceId, String ip, String ua, Long currentSid) {
        boolean member = db.exists("select 1 from workspace_members where workspace_id=:w and user_id=:u and invite_status='ACCEPTED'",
                "w", workspaceId, "u", userId);
        if (!member) throw ApiException.forbidden();
        if (currentSid != null) db.update("update refresh_tokens set revoked_at=:now where id=:id and user_id=:u and revoked_at is null",
                "now", Time.now(), "id", currentSid, "u", userId);
        Row user = db.row("select * from users where id=:id", "id", userId).orElseThrow();
        return issue(user, workspaceId, true, ip, ua);
    }

    public AuthDtos.AuthResponse me(AuthUser au) {
        Row user = db.row("select * from users where id=:id", "id", au.userId()).orElseThrow(() -> ApiException.unauthorized("Please log in."));
        return response(user, au);
    }

    public void logout(String token) {
        if (token != null) db.update("update refresh_tokens set revoked_at=:now where token_hash=:h and revoked_at is null",
                "now", Time.now(), "h", Hashing.sha256(token));
    }

    public List<Map<String, Object>> sessions(long userId, Long currentSid) {
        List<Map<String, Object>> rows = db.list("select id, user_agent, ip_address, created_at, expires_at from refresh_tokens " +
                "where user_id=:u and revoked_at is null and expires_at > :now order by id desc", "u", userId, "now", Time.now());
        for (Map<String, Object> m : rows) m.put("current", currentSid != null && currentSid.equals(((Number) m.get("id")).longValue()));
        return rows;
    }

    public void revokeSession(long userId, long id) {
        int n = db.update("update refresh_tokens set revoked_at=:now where id=:id and user_id=:u and revoked_at is null",
                "now", Time.now(), "id", id, "u", userId);
        if (n == 0) throw ApiException.notFound("Session");
    }

    // ---------------------------------------------------------- password reset

    public void resetPassword(AuthDtos.ResetRequest r, String ip) {
        limiter.check("reset:" + ip, 10, Duration.ofMinutes(15));
        if (!r.newPassword().equals(r.confirmPassword()))
            throw ApiException.invalid("confirmPassword", "password.mismatch", "Passwords do not match.");
        Row u = findUser(r.identifier()).orElseThrow(() -> new ApiException(ErrorCode.OTP_INVALID, "The code is incorrect. Request a new one."));
        otp.verify(u.s("phone"), "RESET_PASSWORD", r.otp());
        if (encoder.matches(r.newPassword(), u.s("password_hash")))
            throw ApiException.invalid("newPassword", "password.same", "New password must be different from the old one.");
        db.update("update users set password_hash=:h, failed_login_count=0, locked_until=null where id=:id",
                "h", encoder.encode(r.newPassword()), "id", u.l("id"));
        db.update("update refresh_tokens set revoked_at=:now where user_id=:u and revoked_at is null", "now", Time.now(), "u", u.l("id"));
        audit.log(null, u.l("id"), "PASSWORD_RESET", "users", u.l("id"), null, null);
        notifier.sms(u.s("phone"), "Your Marketing Panel password was changed. If this wasn't you, contact support.");
    }

    // ------------------------------------------------------------ invite accept

    public Issued acceptInvite(AuthDtos.InviteAcceptRequest r, String ip, String ua) {
        Row inv = db.row("select * from workspace_members where invite_token_hash=:h", "h", Hashing.sha256(r.token()))
                .orElseThrow(() -> ApiException.badRequest("This invite link is not valid."));
        if (!"PENDING".equals(inv.s("invite_status"))) throw ApiException.badRequest("This invite link is not valid.");
        if (inv.t("invite_expires_at").isBefore(Time.now())) throw ApiException.badRequest("This invite has expired. Ask for a new one.");
        String email = inv.s("invite_email");
        Optional<Row> existing = db.row("select * from users where email=:e", "e", email);
        Row user;
        if (existing.isPresent()) {
            user = existing.get();
        } else {
            List<FieldErr> errs = new ArrayList<>();
            if (r.fullName() == null) errs.add(new FieldErr("fullName", "vr.required", "Enter your name."));
            if (r.phone() == null || !Phones.valid(r.phone())) errs.add(new FieldErr("phone", "vr.mobile.invalid", "Enter a valid 10-digit mobile number."));
            if (r.password() == null || !com.marketingpanel.common.validation.StrongPasswordValidator.ok(r.password()))
                errs.add(new FieldErr("password", "vr.password.weak", "Use 8+ characters with upper, lower, number and symbol."));
            if (!errs.isEmpty()) throw ApiException.invalid(errs);
            if (db.exists("select 1 from users where phone=:p", "p", r.phone()))
                throw ApiException.duplicate("phone", "phone.exists", "This number is already registered. Log in instead.");
            long id = db.insertInto("users", "full_name", r.fullName(), "email", email, "phone", r.phone(),
                    "password_hash", encoder.encode(r.password()), "email_verified_at", Time.now(), "account_type", "BUSINESS");
            user = db.row("select * from users where id=:id", "id", id).orElseThrow();
        }
        long ws = inv.l("workspace_id");
        if (db.exists("select 1 from workspace_members where workspace_id=:w and user_id=:u", "w", ws, "u", user.l("id")))
            throw ApiException.duplicate("This person is already in your team.");
        db.update("update workspace_members set user_id=:u, invite_status='ACCEPTED', joined_at=:now, invite_token_hash=null where id=:id",
                "u", user.l("id"), "now", Time.now(), "id", inv.l("id"));
        audit.log(ws, user.l("id"), "INVITE_ACCEPTED", "workspace_members", inv.l("id"), null, null);
        return issue(user, ws, true, ip, ua);
    }
}
