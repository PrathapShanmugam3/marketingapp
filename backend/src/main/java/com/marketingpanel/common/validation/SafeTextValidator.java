package com.marketingpanel.common.validation;

import com.marketingpanel.common.Norm;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** VR-11: no control characters; emoji and Tamil allowed. HTML is stripped on input by Norm.plain. */
public class SafeTextValidator implements ConstraintValidator<SafeText, String> {
    @Override
    public boolean isValid(String v, ConstraintValidatorContext c) {
        return v == null || !Norm.hasControlChars(v);
    }
}
