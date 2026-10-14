package com.marketingpanel.auth;

import com.marketingpanel.common.Norm;
import com.marketingpanel.common.Phones;
import com.marketingpanel.common.validation.IndianMobile;
import com.marketingpanel.common.validation.PersonName;
import com.marketingpanel.common.validation.StrongPassword;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.Map;

public final class AuthDtos {
    private AuthDtos() {}

    public static final String EMAIL_RE = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    public record SignupRequest(
            @NotBlank(message = "{vr.required}") @PersonName String fullName,
            @NotBlank(message = "{vr.required}") @PersonName(digits = true) String businessName,
            @NotBlank(message = "{vr.required}") @Pattern(regexp = "RETAIL|CLINIC|SALON|EDUCATION|REAL_ESTATE|RESTAURANT|AGENCY|OTHER", message = "{vr.required}") String businessType,
            @NotBlank(message = "{vr.required}") @IndianMobile String phone,
            @NotBlank(message = "{vr.required}") @Size(max = 150) @Pattern(regexp = EMAIL_RE, message = "{vr.email.invalid}") String email,
            @NotBlank(message = "{vr.required}") @StrongPassword String password,
            @NotBlank(message = "{vr.required}") String confirmPassword,
            @NotBlank(message = "{vr.required}") @Pattern(regexp = "^[\\p{L}\\p{M} .'-]{2,60}$", message = "{vr.name.invalid}") String city,
            @Size(max = 60) String state,
            @AssertTrue(message = "{vr.required}") boolean acceptTerms,
            @NotBlank(message = "{vr.required}") String captchaToken) {
        public SignupRequest {
            fullName = Norm.name(fullName);
            businessName = Norm.name(businessName);
            phone = Phones.normalizeOrRaw(phone);
            email = Norm.email(email);
            city = Norm.name(city);
            state = Norm.text(state);
        }
    }

    public record OtpVerifyRequest(
            @NotBlank(message = "{vr.required}") @IndianMobile String phone,
            @NotBlank(message = "{vr.required}") @Pattern(regexp = "^\\d{6}$", message = "{vr.otp.invalid}") String otp,
            @NotBlank(message = "{vr.required}") String signupToken) {
        public OtpVerifyRequest {
            phone = Phones.normalizeOrRaw(phone);
        }
    }

    public record OtpSendRequest(@NotBlank(message = "{vr.required}") String target, @NotBlank(message = "{vr.required}") String purpose) {}

    public record LoginRequest(
            @NotBlank(message = "{vr.required}") String identifier,
            String password,
            @Pattern(regexp = "^(\\d{6})?$", message = "{vr.otp.invalid}") String otp,
            boolean rememberMe) {
        public LoginRequest {
            identifier = identifier == null ? null : identifier.trim();
        }
    }

    public record RefreshRequest(Long workspaceId) {}

    public record SelectWorkspaceRequest(@NotNull Long workspaceId) {}

    public record ResetRequest(
            @NotBlank(message = "{vr.required}") String identifier,
            @NotBlank(message = "{vr.required}") @Pattern(regexp = "^\\d{6}$", message = "{vr.otp.invalid}") String otp,
            @NotBlank(message = "{vr.required}") @StrongPassword String newPassword,
            @NotBlank(message = "{vr.required}") String confirmPassword) {}

    public record InviteAcceptRequest(
            @NotBlank String token,
            @PersonName String fullName,
            @IndianMobile String phone,
            @StrongPassword String password) {
        public InviteAcceptRequest {
            phone = Phones.normalizeOrRaw(phone);
            fullName = Norm.name(fullName);
        }
    }

    public record AuthResponse(String accessToken, long expiresIn, Map<String, Object> user, List<Map<String, Object>> workspaces,
                               Map<String, Object> workspace) {}
}
