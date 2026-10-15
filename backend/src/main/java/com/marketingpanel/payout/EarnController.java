package com.marketingpanel.payout;

import com.marketingpanel.auth.*;
import com.marketingpanel.common.*;
import com.marketingpanel.common.validation.StrongPasswordValidator;
import com.marketingpanel.config.Settings;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

/** Promoter portal API (/api/v1/earn/**). */
@RestController
@RequestMapping("/api/v1/earn")
public class EarnController {
    private final PromoterService promoters;
    private final PayoutService payouts;
    private final AuthService authService;
    private final OtpService otp;
    private final Db db;
    private final PasswordEncoder encoder;
    private final Crypto crypto;
    private final RateLimiter limiter;
    private final CaptchaVerifier captcha;
    private final Settings settings;

    public EarnController(PromoterService promoters, PayoutService payouts, AuthService authService, OtpService otp, Db db, PasswordEncoder encoder, Crypto crypto,
                          RateLimiter limiter, CaptchaVerifier captcha, Settings settings) {
        this.promoters = promoters;
        this.payouts = payouts;
        this.authService = authService;
        this.otp = otp;
        this.db = db;
        this.encoder = encoder;
        this.crypto = crypto;
        this.limiter = limiter;
        this.captcha = captcha;
        this.settings = settings;
    }

    public record Signup(String fullName, String phone, String email, String password, String confirmPassword, String referralCode, Boolean acceptTerms,
                         String otp, String signupToken, String captchaToken) {}

    /** Public. Existing users (logged in) just add a promoter profile; new users verify their mobile with an OTP first. */
    @PostMapping("/signup")
    public ResponseEntity<?> signup(@RequestBody Signup r, HttpServletRequest req) {
        if (Auth.present()) return ResponseEntity.ok(promoters.becomePromoter(Auth.userId(), r.referralCode(), Boolean.TRUE.equals(r.acceptTerms())));
        String ip = ClientInfo.ip(req);
        limiter.check("earn-signup:" + ip, 10, Duration.ofHours(1));
        List<FieldErr> errs = new ArrayList<>();
        String name = Norm.name(r.fullName());
        String phone = Phones.e164(r.phone());
        String email = Norm.email(r.email());
        if (name == null || !name.matches("^[\\p{L}\\p{M} .'&-]{2,100}$")) errs.add(new FieldErr("fullName", "vr.name.invalid", "Enter your name."));
        if (phone == null) errs.add(new FieldErr("phone", "vr.mobile.invalid", "Enter a valid 10-digit mobile number."));
        if (email == null || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || email.length() > 150) errs.add(new FieldErr("email", "vr.email.invalid", "Enter a valid email address."));
        if (!StrongPasswordValidator.ok(r.password())) errs.add(new FieldErr("password", "vr.password.weak", "Use 8+ characters with upper, lower, number and symbol."));
        else if (!r.password().equals(r.confirmPassword())) errs.add(new FieldErr("confirmPassword", "password.mismatch", "Passwords do not match."));
        if (!Boolean.TRUE.equals(r.acceptTerms())) errs.add(new FieldErr("acceptTerms", "promoter.terms", "Please accept the promoter terms."));
        if (phone != null && db.exists("select 1 from users where phone=:p", "p", phone)) errs.add(new FieldErr("phone", "phone.exists", "This mobile already has an account. Log in, then join as a promoter."));
        if (email != null && db.exists("select 1 from users where email=:e", "e", email)) errs.add(new FieldErr("email", "email.exists", "This email is already registered."));
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        Long ref = promoters.referredBy(r.referralCode() == null || r.referralCode().isBlank() ? null : r.referralCode().trim().toUpperCase());

        if (r.otp() == null || r.otp().isBlank()) { // step 1: send the OTP
            captcha.verify(r.captchaToken(), ip, "captchaToken");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("fullName", name);
            payload.put("phone", phone);
            payload.put("email", email);
            payload.put("passwordHash", encoder.encode(r.password()));
            payload.put("ref", ref);
            payload.put("exp", System.currentTimeMillis() + 15 * 60_000L);
            OtpService.Sent s = otp.send(phone, "SIGNUP");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("otpRequired", true);
            out.put("signupToken", crypto.seal(Json.write(payload)));
            out.put("otpSentTo", Phones.mask(phone));
            out.put("resendAfterSeconds", s.resendAfterSeconds());
            if (s.devOtp() != null) out.put("devOtp", s.devOtp());
            return ResponseEntity.ok(out);
        }
        // step 2: verify OTP and create the account
        Map<String, Object> p;
        try {
            p = Json.map(crypto.unseal(r.signupToken()));
        } catch (Exception e) {
            throw ApiException.badRequest("Your sign-up session expired. Please start again.");
        }
        if (((Number) p.get("exp")).longValue() < System.currentTimeMillis() || !phone.equals(p.get("phone"))) throw ApiException.badRequest("Your sign-up session expired. Please start again.");
        otp.verify(phone, "SIGNUP", r.otp());
        long userId = db.tx(() -> {
            long uid = db.insertInto("users", "full_name", p.get("fullName"), "email", p.get("email"), "phone", p.get("phone"), "password_hash", p.get("passwordHash"),
                    "phone_verified_at", Time.now(), "account_type", "PROMOTER");
            String code;
            do {
                code = Ids.randomUpper(8);
            } while (db.exists("select 1 from promoters where referral_code=:c", "c", code));
            db.insertInto("promoters", "user_id", uid, "referral_code", code, "referred_by_id", p.get("ref") == null ? null : ((Number) p.get("ref")).longValue());
            return uid;
        });
        Row user = db.row("select * from users where id=:id", "id", userId).orElseThrow();
        AuthService.Issued i = authService.issue(user, null, true, ip, ClientInfo.userAgent());
        ResponseCookie c = ResponseCookie.from("mp_rt", i.refreshToken()).httpOnly(true).secure(settings.cookieSecure).sameSite("Strict").path("/api/v1/auth").maxAge(i.refreshTtl()).build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, c.toString()).body(i.body());
    }

    @GetMapping("/profile")
    @PreAuthorize(Roles.PROMOTER)
    public Map<String, Object> profile() {
        return promoters.profile(Auth.userId());
    }

    @PostMapping("/kyc")
    @PreAuthorize(Roles.PROMOTER)
    public Map<String, Object> kyc(@RequestBody PromoterService.KycRequest r) {
        return promoters.verifyKyc(Auth.userId(), r);
    }

    public record PostbackUrl(String postbackUrl) {}

    @PutMapping("/postback-url")
    @PreAuthorize(Roles.PROMOTER)
    public Map<String, Object> postbackUrl(@RequestBody PostbackUrl r) {
        promoters.setPostbackUrl(Auth.userId(), r.postbackUrl());
        return Map.of("ok", true);
    }

    @GetMapping("/offers")
    @PreAuthorize(Roles.PROMOTER)
    public List<Map<String, Object>> offers(@RequestParam(required = false) String q, @RequestParam(required = false) String eventType, @RequestParam(required = false) String category,
                                            @RequestParam(required = false) BigDecimal minPayout, @RequestParam(required = false) BigDecimal maxPayout) {
        return promoters.offerWall(Auth.userId(), q, eventType, category, minPayout, maxPayout);
    }

    @GetMapping("/stats")
    @PreAuthorize(Roles.PROMOTER)
    public Map<String, Object> stats() {
        return promoters.stats(Auth.userId());
    }

    @GetMapping("/ledger")
    @PreAuthorize(Roles.PROMOTER)
    public List<Map<String, Object>> ledger() {
        return promoters.ledger(Auth.userId());
    }

    public record OtpRequest(String purpose) {}

    @PostMapping("/otp")
    @PreAuthorize(Roles.PROMOTER)
    public Map<String, Object> otp(@RequestBody OtpRequest r) {
        return promoters.sendOtp(Auth.userId(), r.purpose());
    }

    @GetMapping("/payout-methods")
    @PreAuthorize(Roles.PROMOTER)
    public List<Map<String, Object>> methods() {
        return promoters.methods(Auth.userId());
    }

    @PostMapping("/payout-methods")
    @PreAuthorize(Roles.PROMOTER)
    public Map<String, Object> addMethod(@RequestBody PromoterService.MethodRequest r) {
        return promoters.addMethod(Auth.userId(), r);
    }

    @DeleteMapping("/payout-methods/{id}")
    @PreAuthorize(Roles.PROMOTER)
    public Map<String, Object> deleteMethod(@PathVariable long id) {
        promoters.deleteMethod(Auth.userId(), id);
        return Map.of("ok", true);
    }

    @PostMapping("/payout-methods/{id}/default")
    @PreAuthorize(Roles.PROMOTER)
    public Map<String, Object> makeDefault(@PathVariable long id) {
        promoters.makeDefault(Auth.userId(), id);
        return Map.of("ok", true);
    }

    @PostMapping("/withdrawals")
    @PreAuthorize(Roles.PROMOTER)
    public Map<String, Object> withdraw(@RequestBody PayoutService.WithdrawRequest r) {
        return payouts.withdraw(Auth.userId(), r);
    }

    @GetMapping("/withdrawals")
    @PreAuthorize(Roles.PROMOTER)
    public List<Map<String, Object>> withdrawals() {
        return payouts.list(Auth.userId());
    }
}
