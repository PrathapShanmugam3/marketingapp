package com.marketingpanel.auth;

import com.marketingpanel.common.*;
import com.marketingpanel.common.validation.IndianMobile;
import com.marketingpanel.common.validation.PersonName;
import com.marketingpanel.common.validation.StrongPassword;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** P23 My Profile & Security. */
@RestController
@RequestMapping("/api/v1/profile")
@PreAuthorize(Roles.AUTHENTICATED)
public class ProfileController {
    private final Db db;
    private final PasswordEncoder encoder;
    private final OtpService otp;
    private final AuditService audit;

    public ProfileController(Db db, PasswordEncoder encoder, OtpService otp, AuditService audit) {
        this.db = db;
        this.encoder = encoder;
        this.otp = otp;
        this.audit = audit;
    }

    public record UpdateProfile(@NotBlank(message = "{vr.required}") @PersonName String fullName,
                                @Pattern(regexp = "en|ta", message = "{vr.required}") String preferredLanguage) {
        public UpdateProfile {
            fullName = Norm.name(fullName);
        }
    }

    public record PhoneOtp(@NotBlank @IndianMobile String phone) {
        public PhoneOtp {
            phone = Phones.normalizeOrRaw(phone);
        }
    }

    public record ChangePhone(@NotBlank @IndianMobile String phone, @NotBlank @Pattern(regexp = "^\\d{6}$", message = "{vr.otp.invalid}") String otp) {
        public ChangePhone {
            phone = Phones.normalizeOrRaw(phone);
        }
    }

    public record ChangePassword(@NotBlank String currentPassword, @NotBlank @StrongPassword String newPassword,
                                 @NotBlank String confirmPassword) {}

    @PutMapping
    public Map<String, Object> update(@Valid @RequestBody UpdateProfile r) {
        db.update("update users set full_name=:n, preferred_language=coalesce(:l, preferred_language) where id=:id",
                "n", r.fullName(), "l", r.preferredLanguage(), "id", Auth.userId());
        return Map.of("ok", true);
    }

    @PostMapping("/phone/otp")
    public Map<String, Object> phoneOtp(@Valid @RequestBody PhoneOtp r) {
        if (db.exists("select 1 from users where phone=:p and id<>:id", "p", r.phone(), "id", Auth.userId()))
            throw ApiException.duplicate("phone", "phone.exists", "This number is already registered.");
        OtpService.Sent s = otp.send(r.phone(), "CHANGE_PHONE");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resendAfterSeconds", s.resendAfterSeconds());
        if (s.devOtp() != null) m.put("devOtp", s.devOtp());
        return m;
    }

    @PutMapping("/phone")
    public Map<String, Object> changePhone(@Valid @RequestBody ChangePhone r) {
        otp.verify(r.phone(), "CHANGE_PHONE", r.otp());
        db.update("update users set phone=:p, phone_verified_at=:now where id=:id", "p", r.phone(), "now", Time.now(), "id", Auth.userId());
        audit.log("PHONE_CHANGED", "users", Auth.userId());
        return Map.of("ok", true);
    }

    @PostMapping("/password")
    public Map<String, Object> changePassword(@Valid @RequestBody ChangePassword r) {
        Row u = db.row("select * from users where id=:id", "id", Auth.userId()).orElseThrow();
        if (!encoder.matches(r.currentPassword(), u.s("password_hash")))
            throw ApiException.invalid("currentPassword", "password.wrong", "Current password is incorrect.");
        if (!r.newPassword().equals(r.confirmPassword()))
            throw ApiException.invalid("confirmPassword", "password.mismatch", "Passwords do not match.");
        if (encoder.matches(r.newPassword(), u.s("password_hash")))
            throw ApiException.invalid("newPassword", "password.same", "New password must be different from the current one.");
        db.update("update users set password_hash=:h where id=:id", "h", encoder.encode(r.newPassword()), "id", u.l("id"));
        Long sid = Auth.user().sessionId();
        db.update("update refresh_tokens set revoked_at=:now where user_id=:u and revoked_at is null and id<>:sid",
                "now", Time.now(), "u", u.l("id"), "sid", sid == null ? -1L : sid);
        audit.log("PASSWORD_CHANGED", "users", u.l("id"));
        return Map.of("ok", true);
    }
}
