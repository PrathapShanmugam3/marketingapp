package com.marketingpanel.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = SafeTextValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeText {
    String message() default "{vr.text.unsupported}";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
