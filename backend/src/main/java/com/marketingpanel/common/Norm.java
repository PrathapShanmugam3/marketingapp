package com.marketingpanel.common;

import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;

import java.util.regex.Pattern;

/** Global input normalisation (SRS 6.1): trim, collapse spaces in names, lower-case email, strip HTML. */
public final class Norm {
    private static final PolicyFactory NO_HTML = new org.owasp.html.HtmlPolicyBuilder().toFactory();
    private static final PolicyFactory BASIC = Sanitizers.FORMATTING.and(Sanitizers.BLOCKS).and(Sanitizers.LINKS);
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s{2,}");

    private Norm() {}

    public static String text(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    public static String name(String s) {
        if (s == null) return null;
        return MULTI_SPACE.matcher(s.trim()).replaceAll(" ");
    }

    public static String email(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase();
        return t.isEmpty() ? null : t;
    }

    /** Strips all HTML; the result is plain text safe for storage and rendering. */
    public static String plain(String s) {
        if (s == null) return null;
        String out = NO_HTML.sanitize(s);
        return org.apache.commons.text.StringEscapeUtils.unescapeHtml4(out).trim();
    }

    /** Allows basic formatting only; used for landing page text blocks. */
    public static String basicHtml(String s) {
        return s == null ? null : BASIC.sanitize(s);
    }

    public static boolean hasControlChars(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 && c != '\n' && c != '\r' && c != '\t') return true;
            if (c == 127) return true;
        }
        return false;
    }
}
