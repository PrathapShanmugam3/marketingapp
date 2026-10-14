package com.marketingpanel.billing;

import com.marketingpanel.common.Money;

import java.math.BigDecimal;

/** GST 18%: CGST 9% + SGST 9% when the buyer is in the seller's state, otherwise IGST 18%. */
public record Gst(BigDecimal taxable, BigDecimal cgst, BigDecimal sgst, BigDecimal igst, BigDecimal total) {
    public static Gst of(BigDecimal taxable, String buyerState, String sellerState) {
        BigDecimal t = Money.inr(taxable);
        if (buyerState != null && buyerState.equalsIgnoreCase(sellerState)) {
            BigDecimal half = Money.inr(t.multiply(Money.HALF_GST));
            return new Gst(t, half, half, BigDecimal.ZERO.setScale(2), t.add(half).add(half));
        }
        BigDecimal igst = Money.inr(t.multiply(Money.GST_RATE));
        return new Gst(t, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), igst, t.add(igst));
    }

    public BigDecimal tax() {
        return cgst.add(sgst).add(igst);
    }
}
