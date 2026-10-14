package com.marketingpanel.payout;

import java.util.Arrays;

/** Fuzzy name comparison for PAN / payout account checks (score 0-100, accepted at >= 80). */
public final class NameMatch {
    private NameMatch() {}

    private static String norm(String s) {
        if (s == null) return "";
        return s.toUpperCase().replaceAll("[^\\p{L} ]", " ").replaceAll("\\s+", " ").trim();
    }

    public static int score(String a, String b) {
        String x = norm(a), y = norm(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        int plain = ratio(x, y);
        String[] tx = x.split(" "), ty = y.split(" ");
        Arrays.sort(tx);
        Arrays.sort(ty);
        int sorted = ratio(String.join(" ", tx), String.join(" ", ty));
        return Math.max(plain, sorted);
    }

    private static int ratio(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 100;
        return (int) Math.round(100.0 * (max - lev(a, b)) / max);
    }

    private static int lev(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
