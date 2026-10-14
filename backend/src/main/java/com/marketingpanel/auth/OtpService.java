package com.marketingpanel.auth;

import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/** VR-05: 6 digits, valid 5 min, 5 wrong tries, resend after 30 s, 3 sends per 15 min per target. */
@Service
public class OtpService {
    private final Db db;
    private final PasswordEncoder encoder;
    private final Notifier notifier;
    private final Settings settings;

    public OtpService(Db db, PasswordEncoder encoder, Notifier notifier, Settings settings) {
        this.db = db;
        this.encoder = encoder;
        this.notifier = notifier;
        this.settings = settings;
    }

    public record Sent(int resendAfterSeconds, String devOtp) {}

    public Sent send(String targetPhone, String purpose) {
        LocalDateTime now = Time.now();
        Optional<Row> last = db.row("select created_at from otp_codes where target=:t and purpose=:p order by id desc limit 1",
                "t", targetPhone, "p", purpose);
        if (last.isPresent() && last.get().t("created_at").isAfter(now.minusSeconds(30))) {
            long wait = 30 - Duration.between(last.get().t("created_at"), now).toSeconds();
            throw ApiException.rateLimited((int) Math.max(1, wait));
        }
        long recent = db.count("select count(*) from otp_codes where target=:t and purpose=:p and created_at > :since", "t", targetPhone, "p", purpose,
                "since", now.minusMinutes(15));
        if (recent >= 3) throw ApiException.rateLimited(15 * 60);
        String code = Ids.otp();
        db.insert("insert into otp_codes (target,purpose,code_hash,expires_at,ip_address) values (:t,:p,:h,:e,:ip)",
                "t", targetPhone, "p", purpose, "h", encoder.encode(code), "e", now.plusMinutes(5), "ip", ClientInfo.ip());
        notifier.sms(targetPhone, "Your Marketing Panel code is " + code + ". It is valid for 5 minutes. Do not share it.");
        return new Sent(30, settings.otpDevEcho ? code : null);
    }

    /** Verifies and consumes the newest unexpired code for this target and purpose. */
    public void verify(String targetPhone, String purpose, String code) {
        LocalDateTime now = Time.now();
        Row r = db.row("select * from otp_codes where target=:t and purpose=:p and consumed_at is null order by id desc limit 1",
                "t", targetPhone, "p", purpose).orElseThrow(() -> otpInvalid("The code is incorrect. Request a new one.", 0));
        if (r.t("expires_at").isBefore(now)) throw otpInvalid("This code has expired. Tap Resend.", 0);
        int attempts = r.i("attempts");
        if (attempts >= 5) throw otpInvalid("Too many wrong tries. Request a new code.", 0);
        if (code == null || !code.matches("^\\d{6}$") || !encoder.matches(code, r.s("code_hash"))) {
            db.update("update otp_codes set attempts=attempts+1 where id=:id", "id", r.l("id"));
            int left = 4 - attempts;
            throw otpInvalid(left <= 0 ? "Too many wrong tries. Request a new code." : "The code is incorrect. " + left + " tries left.", left);
        }
        int n = db.update("update otp_codes set consumed_at=:now where id=:id and consumed_at is null", "now", now, "id", r.l("id"));
        if (n == 0) throw otpInvalid("The code is incorrect. Request a new one.", 0);
    }

    private ApiException otpInvalid(String msg, int triesLeft) {
        return new ApiException(ErrorCode.OTP_INVALID, msg, java.util.List.of(new FieldErr("otp", "otp.invalid", msg)),
                java.util.Map.of("triesLeft", Math.max(0, triesLeft)), null);
    }
}
