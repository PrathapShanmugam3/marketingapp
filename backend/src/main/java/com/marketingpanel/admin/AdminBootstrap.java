package com.marketingpanel.admin;

import com.marketingpanel.common.Db;
import com.marketingpanel.common.Phones;
import com.marketingpanel.common.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first platform staff account from environment variables so a fresh install has someone who can open the admin console.
 * Does nothing when the variables are not set or when a user with that email already exists. Remove the variables after the first start.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private final Db db;
    private final PasswordEncoder encoder;
    private final String email;
    private final String phone;
    private final String password;
    private final String name;
    private final String role;

    public AdminBootstrap(Db db, PasswordEncoder encoder,
                          @Value("${app.admin.bootstrap-email:}") String email, @Value("${app.admin.bootstrap-phone:}") String phone,
                          @Value("${app.admin.bootstrap-password:}") String password, @Value("${app.admin.bootstrap-name:Platform Admin}") String name,
                          @Value("${app.admin.bootstrap-role:SUPER_ADMIN}") String role) {
        this.db = db;
        this.encoder = encoder;
        this.email = email.trim().toLowerCase();
        this.phone = phone;
        this.password = password;
        this.name = name;
        this.role = role == null || role.isBlank() ? "SUPER_ADMIN" : role.trim().toUpperCase();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isEmpty() || password.isEmpty()) return;
        String e164 = Phones.e164(phone == null ? "" : phone.trim());
        if (e164 == null) {
            log.error("Admin bootstrap skipped: ADMIN_BOOTSTRAP_PHONE '{}' is not a valid mobile number.", phone);
            return;
        }
        if (!java.util.List.of("SUPER_ADMIN", "FINANCE", "SUPPORT").contains(role)) {
            log.error("Admin bootstrap skipped: ADMIN_BOOTSTRAP_ROLE '{}' must be SUPER_ADMIN, FINANCE or SUPPORT.", role);
            return;
        }
        if (password.length() < 12) {
            log.error("Admin bootstrap skipped: ADMIN_BOOTSTRAP_PASSWORD must be at least 12 characters.");
            return;
        }
        if (db.exists("select 1 from users where email=:e or phone=:p", "e", email, "p", e164)) {
            log.info("Admin bootstrap: a user with this email or phone already exists, nothing to do.");
            return;
        }
        db.insertInto("users", "full_name", name, "email", email, "phone", e164, "password_hash", encoder.encode(password),
                "email_verified_at", Time.now(), "phone_verified_at", Time.now(), "admin_role", role, "account_type", "BUSINESS");
        log.warn("Admin bootstrap: created {} account for {}. Remove the ADMIN_BOOTSTRAP_* variables now.", role, email);
    }
}
