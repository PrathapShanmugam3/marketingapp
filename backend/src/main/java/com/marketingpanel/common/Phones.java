package com.marketingpanel.common;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

/** VR-02: Indian mobile (10 digits starting 6-9) or any valid mobile; stored as E.164. */
public final class Phones {
    private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();

    private Phones() {}

    /** Returns E.164 or null when invalid. defaultCountryRegion e.g. "IN". */
    public static String e164(String raw, String defaultRegion) {
        if (raw == null) return null;
        String s = raw.trim().replaceAll("[\\s\\-().]", "");
        if (s.isEmpty()) return null;
        try {
            Phonenumber.PhoneNumber n;
            if (s.startsWith("+")) {
                n = UTIL.parse(s, null);
            } else if (s.startsWith("00")) {
                n = UTIL.parse("+" + s.substring(2), null);
            } else {
                n = UTIL.parse(s, defaultRegion);
            }
            if (n.getCountryCode() == 91) {
                String nat = String.valueOf(n.getNationalNumber());
                if (!nat.matches("[6-9]\\d{9}")) return null;
            } else {
                if (!UTIL.isValidNumber(n)) return null;
                var t = UTIL.getNumberType(n);
                if (t != PhoneNumberUtil.PhoneNumberType.MOBILE && t != PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE)
                    return null;
            }
            return UTIL.format(n, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException e) {
            return null;
        }
    }

    public static String e164(String raw) {
        return e164(raw, "IN");
    }

    public static boolean valid(String raw) {
        return e164(raw) != null;
    }

    /** Normalise if valid, otherwise return the trimmed input so validation can report it. */
    public static String normalizeOrRaw(String raw) {
        if (raw == null) return null;
        String n = e164(raw);
        return n != null ? n : raw.trim();
    }

    public static String mask(String phone) {
        if (phone == null || phone.length() < 6) return "***";
        return phone.substring(0, phone.length() - 6) + "****" + phone.substring(phone.length() - 2);
    }
}
