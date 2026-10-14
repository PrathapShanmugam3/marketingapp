package com.marketingpanel.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = PincodeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Pincode {
    String message() default "{vr.pincode.invalid}";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
