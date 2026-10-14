package com.marketingpanel.payout;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** PAN verification port (gateway or KYC API). The sandbox adapter echoes the name unless the PAN ends in Z. */
public interface KycVerifier {
    /** Returns the name registered against the PAN, or null if the PAN does not exist. */
    String verifyPan(String pan, String declaredName, LocalDate dob);

    @Component
    @ConditionalOnMissingBean(value = KycVerifier.class, ignored = Sandbox.class)
    class Sandbox implements KycVerifier {
        @Override
        public String verifyPan(String pan, String declaredName, LocalDate dob) {
            if (pan.endsWith("Z")) return "SOMEONE ELSE ENTIRELY";
            return declaredName;
        }
    }
}
