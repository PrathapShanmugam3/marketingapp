package com.marketingpanel.common;

import com.github.f4b6a3.ulid.UlidCreator;

import java.security.SecureRandom;

public final class Ids {
    private static final SecureRandom RND = new SecureRandom();
    private static final String ALNUM = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private Ids() {}

    public static String ulid() {
        return UlidCreator.getUlid().toString();
    }

    public static String randomCode(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ALNUM.charAt(RND.nextInt(ALNUM.length())));
        return sb.toString();
    }

    public static String randomUpper(int len) {
        String a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(a.charAt(RND.nextInt(a.length())));
        return sb.toString();
    }

    public static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RND.nextBytes(b);
        return Hashing.hex(b);
    }

    public static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RND.nextBytes(b);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String otp() {
        return String.format("%06d", RND.nextInt(1_000_000));
    }
}
