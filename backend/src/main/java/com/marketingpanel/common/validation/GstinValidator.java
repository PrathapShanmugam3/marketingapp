package com.marketingpanel.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

/** VR-06 format check. The state-code match is checked by the service because it needs the chosen state. */
public class GstinValidator implements ConstraintValidator<Gstin, String> {
    public static final Pattern P = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");

    @Override
    public boolean isValid(String v, ConstraintValidatorContext c) {
        return v == null || v.isBlank() || P.matcher(v).matches();
    }
}
