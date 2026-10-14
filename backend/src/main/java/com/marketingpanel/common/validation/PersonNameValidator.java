package com.marketingpanel.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

/** VR-01: 2-100 chars; letters in any script (incl. Tamil), spaces, . ' & - only. */
public class PersonNameValidator implements ConstraintValidator<PersonName, String> {
    private static final Pattern P = Pattern.compile("^[\\p{L}\\p{M} .'&-]{2,100}$");
    private static final Pattern P_DIGITS = Pattern.compile("^[\\p{L}\\p{M}\\p{Nd} .'&,()/-]{2,100}$");
    private boolean digits;

    @Override
    public void initialize(PersonName a) {
        this.digits = a.digits();
    }

    @Override
    public boolean isValid(String v, ConstraintValidatorContext c) {
        if (v == null || v.isBlank()) return true;
        return (digits ? P_DIGITS : P).matcher(v).matches() && v.codePoints().anyMatch(Character::isLetter);
    }
}
