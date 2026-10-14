package com.marketingpanel.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

public final class Money {
    public static final BigDecimal GST_RATE = new BigDecimal("0.18");
    public static final BigDecimal HALF_GST = new BigDecimal("0.09");

    private Money() {}

    public static BigDecimal inr(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal rate(BigDecimal v) {
        return v.setScale(4, RoundingMode.HALF_UP);
    }

    public static BigDecimal bd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        return new BigDecimal(o.toString());
    }

    public static String format(BigDecimal v) {
        return "₹" + NumberFormat.getNumberInstance(new Locale("en", "IN")).format(inr(v));
    }
}
