package com.marketingpanel.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** VR-04: 8-64 chars, upper, lower, digit, symbol, no leading/trailing spaces, not in the breached list. */
public class StrongPasswordValidator implements ConstraintValidator<StrongPassword, String> {
    private static final Set<String> COMMON = load();

    private static Set<String> load() {
        Set<String> s = new HashSet<>();
        try (var in = StrongPasswordValidator.class.getResourceAsStream("/common-passwords.txt")) {
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) s.add(line.trim().toLowerCase());
            }
        } catch (Exception ignored) {
            // list is best-effort
        }
        return s;
    }

    public static boolean ok(String v) {
        if (v == null) return false;
        if (v.length() < 8 || v.length() > 64) return false;
        if (!v.equals(v.trim())) return false;
        boolean up = false, lo = false, dg = false, sy = false;
        for (char ch : v.toCharArray()) {
            if (Character.isUpperCase(ch)) up = true;
            else if (Character.isLowerCase(ch)) lo = true;
            else if (Character.isDigit(ch)) dg = true;
            else if (!Character.isWhitespace(ch)) sy = true;
        }
        return up && lo && dg && sy && !COMMON.contains(v.toLowerCase());
    }

    @Override
    public boolean isValid(String v, ConstraintValidatorContext c) {
        if (v == null || v.isEmpty()) return true;
        return ok(v);
    }
}
