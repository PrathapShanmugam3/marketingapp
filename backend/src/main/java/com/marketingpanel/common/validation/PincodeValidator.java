package com.marketingpanel.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PincodeValidator implements ConstraintValidator<Pincode, String> {
    @Override
    public boolean isValid(String v, ConstraintValidatorContext c) {
        return v == null || v.isBlank() || v.matches("^[1-9][0-9]{5}$");
    }
}
