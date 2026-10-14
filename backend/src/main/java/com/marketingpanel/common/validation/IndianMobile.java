package com.marketingpanel.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = IndianMobileValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface IndianMobile {
    String message() default "{vr.mobile.invalid}";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
