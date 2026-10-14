package com.marketingpanel.common.validation;

import com.marketingpanel.common.Phones;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class IndianMobileValidator implements ConstraintValidator<IndianMobile, String> {
    @Override
    public boolean isValid(String v, ConstraintValidatorContext c) {
        if (v == null || v.isBlank()) return true; // @NotBlank handles empty
        return Phones.valid(v);
    }
}
