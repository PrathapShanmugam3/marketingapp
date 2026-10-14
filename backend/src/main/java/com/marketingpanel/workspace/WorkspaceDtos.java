package com.marketingpanel.workspace;

import com.marketingpanel.common.Norm;
import com.marketingpanel.common.Phones;
import com.marketingpanel.common.validation.*;
import jakarta.validation.constraints.*;

public final class WorkspaceDtos {
    private WorkspaceDtos() {}

    public record UpdateWorkspace(
            @NotBlank(message = "{vr.required}") @PersonName(digits = true) String name,
            @NotBlank(message = "{vr.required}") @IndianMobile String phone,
            @Pattern(regexp = "^$|^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "{vr.email.invalid}") @Size(max = 150) String email,
            @Size(max = 200, message = "{vr.size}") @SafeText String addressLine,
            @NotBlank(message = "{vr.required}") @Pattern(regexp = "^[\\p{L}\\p{M} .'-]{2,60}$", message = "{vr.name.invalid}") String city,
            @NotBlank(message = "{vr.required}") String state,
            @Pincode String pincode,
            @Gstin String gstin,
            @NotBlank(message = "{vr.required}") @Pattern(regexp = "en|ta", message = "{vr.required}") String language,
            @Size(max = 500) String logoUrl,
            @Pattern(regexp = "^$|^#[0-9a-fA-F]{6}$", message = "{vr.required}") String brandColor) {
        public UpdateWorkspace {
            name = Norm.name(name);
            phone = Phones.normalizeOrRaw(phone);
            email = Norm.email(email);
            addressLine = Norm.plain(addressLine);
            city = Norm.name(city);
            gstin = gstin == null || gstin.isBlank() ? null : gstin.trim().toUpperCase();
            pincode = Norm.text(pincode);
            logoUrl = Norm.text(logoUrl);
            brandColor = Norm.text(brandColor);
        }
    }

    public record InviteRequest(
            @NotBlank(message = "{vr.required}") @Size(max = 150) @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "{vr.email.invalid}") String email,
            @NotBlank(message = "{vr.required}") @Pattern(regexp = "MANAGER|STAFF", message = "{vr.required}") String role) {
        public InviteRequest {
            email = Norm.email(email);
        }
    }

    public record RoleChange(@NotBlank @Pattern(regexp = "MANAGER|STAFF|OWNER", message = "{vr.required}") String role) {}
}
