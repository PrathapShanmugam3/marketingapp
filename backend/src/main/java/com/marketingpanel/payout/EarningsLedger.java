package com.marketingpanel.payout;

import com.marketingpanel.common.ApiException;
import com.marketingpanel.common.Db;
import com.marketingpanel.common.Row;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Append-only promoter ledger (SRS 4.4). Every balance change is one ledger row written together with the cached
 * balance column under a row lock on the promoter, so two parallel debits can never overspend.
 */
@Service
public class EarningsLedger {
    private final Db db;

    public EarningsLedger(Db db) {
        this.db = db;
    }

    /** signed: positive credits the bucket, negative debits it. Must run inside a transaction. */
    public BigDecimal post(long promoterId, String type, BigDecimal signed, String bucket, Long conversionId, Long payoutId, String note) {
        Row p = db.row("select balance_pending, balance_available from promoters where id=:id for update", "id", promoterId).orElseThrow(() -> ApiException.notFound("Promoter"));
        String col = "PENDING".equals(bucket) ? "balance_pending" : "balance_available";
        BigDecimal after = p.d(col).add(signed);
        if ("PAYOUT".equals(type) && after.signum() < 0) throw ApiException.insufficient("You can withdraw up to " + p.d(col) + " from your available balance.");
        db.update("update promoters set " + col + "=:b" + ("EARNING".equals(type) ? ", lifetime_earned=lifetime_earned+:s" : "") +
                ("REVERSAL".equals(type) ? ", lifetime_earned=lifetime_earned+:s" : "") + " where id=:id", "b", after, "s", signed, "id", promoterId);
        db.insertInto("earnings_ledger", "promoter_id", promoterId, "type", type, "amount", signed, "bucket", bucket, "balance_after", after,
                "conversion_id", conversionId, "payout_id", payoutId, "note", note == null ? null : (note.length() > 255 ? note.substring(0, 255) : note));
        return after;
    }

    /** Pending -> available once the hold period ends (or the advertiser approves early). */
    public void releaseHold(long promoterId, BigDecimal amount, long conversionId) {
        post(promoterId, "HOLD_RELEASE", amount.negate(), "PENDING", conversionId, null, "Hold released");
        post(promoterId, "HOLD_RELEASE", amount, "AVAILABLE", conversionId, null, "Hold released");
    }

    /** NFR-08: balance columns must equal the ledger. Returns a description of every difference. */
    public java.util.List<String> verify() {
        java.util.List<String> diffs = new java.util.ArrayList<>();
        for (Row r : db.rows("select p.id, p.balance_pending, p.balance_available, " +
                "coalesce((select sum(amount) from earnings_ledger l where l.promoter_id=p.id and l.bucket='PENDING'),0) as lp, " +
                "coalesce((select sum(amount) from earnings_ledger l where l.promoter_id=p.id and l.bucket='AVAILABLE'),0) as la from promoters p")) {
            if (r.d("balance_pending").compareTo(r.d("lp")) != 0 || r.d("balance_available").compareTo(r.d("la")) != 0)
                diffs.add("promoter " + r.l("id") + ": pending " + r.d("balance_pending") + " vs ledger " + r.d("lp") + ", available " + r.d("balance_available") + " vs ledger " + r.d("la"));
        }
        return diffs;
    }
}
