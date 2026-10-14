package com.marketingpanel.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = PersonNameValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PersonName {
    boolean digits() default false;

    String message() default "{vr.name.invalid}";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
