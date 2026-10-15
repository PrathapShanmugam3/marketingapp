package com.marketingpanel.crm;

import com.marketingpanel.common.Norm;
import com.marketingpanel.common.Phones;
import com.marketingpanel.common.validation.IndianMobile;
import com.marketingpanel.common.validation.PersonName;
import com.marketingpanel.common.validation.SafeText;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class CrmDtos {
    private CrmDtos() {}

    public record ContactRequest(
            @NotBlank(message = "{vr.required}") @PersonName String fullName,
            @NotBlank(message = "{vr.required}") @IndianMobile String phone,
            @Size(max = 150) @Pattern(regexp = "^$|^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "{vr.email.invalid}") String email,
            @Size(max = 60, message = "{vr.size}") String city,
            @Past(message = "{vr.required}") LocalDate dateOfBirth,
            @Pattern(regexp = "^(en|ta)?$", message = "{vr.required}") String language,
            @Size(max = 20, message = "{vr.size}") List<Long> tagIds,
            boolean whatsappConsent,
            @Pattern(regexp = "^(MANUAL|QR|FORM|WHATSAPP)?$", message = "{vr.required}") String optInSource,
            Map<String, String> customFields) {
        public ContactRequest {
            fullName = Norm.name(fullName);
            phone = Phones.normalizeOrRaw(phone);
            email = Norm.email(email);
            city = Norm.name(city);
            language = language == null || language.isBlank() ? null : language;
            optInSource = optInSource == null || optInSource.isBlank() ? null : optInSource;
        }
    }

    public record TagRequest(
            @NotBlank(message = "{vr.required}") @Size(min = 1, max = 40, message = "{vr.size}") @SafeText String name,
            @NotBlank(message = "{vr.required}") @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "{vr.required}") String color) {
        public TagRequest {
            name = Norm.plain(Norm.name(name));
        }
    }

    public record SegmentRequest(
            @NotBlank(message = "{vr.required}") @Size(min = 3, max = 60, message = "{vr.size}") @SafeText String name,
            @NotNull(message = "{vr.required}") com.fasterxml.jackson.databind.JsonNode filter) {
        public SegmentRequest {
            name = Norm.plain(Norm.name(name));
        }
    }

    public record BulkRequest(
            @NotBlank @Pattern(regexp = "DELETE|TAG|UNTAG") String action,
            List<Long> ids,
            boolean allMatching,
            Map<String, Object> filter,
            Long tagId,
            String confirm) {}

    public record LeadRequest(
            Long contactId,
            ContactRequest newContact,
            @NotBlank(message = "{vr.required}") @Pattern(regexp = "FORM|WHATSAPP|CALL|WALK_IN|INSTAGRAM|FACEBOOK|REFERRAL|OTHER", message = "{vr.required}") String source,
            @Size(max = 1000, message = "{vr.size}") @SafeText String requirement,
            @DecimalMin(value = "0", message = "{vr.money.invalid}") @DecimalMax(value = "99999999.99", message = "{vr.money.invalid}") java.math.BigDecimal expectedValue,
            Long assignedTo,
            String followUpAt) {
        public LeadRequest {
            requirement = Norm.plain(requirement);
        }
    }

    public record LeadPatch(
            @Pattern(regexp = "^(NEW|CONTACTED|INTERESTED|WON|LOST)?$", message = "{vr.required}") String status,
            @Size(max = 255, message = "{vr.size}") String lostReason,
            Long assignedTo,
            Boolean clearAssignee,
            String followUpAt,
            Boolean clearFollowUp,
            @DecimalMin(value = "0", message = "{vr.money.invalid}") @DecimalMax(value = "99999999.99", message = "{vr.money.invalid}") java.math.BigDecimal expectedValue,
            @Size(max = 1000, message = "{vr.size}") String requirement) {}

    public record NoteRequest(
            @NotBlank(message = "Note cannot be empty.") @Size(min = 1, max = 1000, message = "{vr.size}") @SafeText String note,
            @Pattern(regexp = "^(NOTE|CALL|MESSAGE)?$") String type) {
        public NoteRequest {
            note = Norm.plain(note);
        }
    }

    public record FormField(
            @NotBlank String key,
            @NotBlank @Size(min = 1, max = 50, message = "{vr.size}") String label,
            @NotBlank @Pattern(regexp = "text|number|select|date", message = "{vr.required}") String type,
            boolean required,
            List<String> options) {}

    public record FormRequest(
            @NotBlank(message = "{vr.required}") @Size(min = 3, max = 60, message = "{vr.size}") @SafeText String name,
            @NotBlank(message = "{vr.required}") @Pattern(regexp = "^[a-z0-9-]{3,50}$", message = "{vr.required}") String slug,
            @NotBlank(message = "{vr.required}") @Size(min = 3, max = 100, message = "{vr.size}") @SafeText String title,
            @Size(max = 500, message = "{vr.size}") @SafeText String description,
            @NotNull @Size(max = 10, message = "A form can have up to 10 fields.") List<@jakarta.validation.Valid FormField> fields,
            @NotBlank(message = "Add the consent text customers agree to.") @Size(min = 10, max = 300, message = "{vr.size}") @SafeText String consentText,
            @Size(max = 200, message = "{vr.size}") @SafeText String successMessage,
            Long autoReplyTemplateId,
            Long defaultTagId,
            Boolean active) {
        public FormRequest {
            name = Norm.plain(name);
            title = Norm.plain(title);
            description = Norm.plain(description);
            consentText = Norm.plain(consentText);
            successMessage = Norm.plain(successMessage);
        }
    }
}
