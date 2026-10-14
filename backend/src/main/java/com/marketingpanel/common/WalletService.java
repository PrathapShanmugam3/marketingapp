package com.marketingpanel.common;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Message-credit wallet (SRS 4.4). The workspace balance is a cache of the append-only
 * wallet_transactions ledger; both change in the same DB transaction under a row lock.
 */
@Service
public class WalletService {
    private static final BigDecimal LOW = new BigDecimal("100");
    private final Db db;
    private final NotificationService notifications;

    public WalletService(Db db, NotificationService notifications) {
        this.db = db;
        this.notifications = notifications;
    }

    public BigDecimal balance(long ws) {
        BigDecimal b = db.scalar("select wallet_balance from workspaces where id=:id", BigDecimal.class, "id", ws);
        if (b == null) throw ApiException.notFound("Workspace");
        return b;
    }

    public BigDecimal credit(long ws, BigDecimal amount, String refType, Long refId, String note, Long by) {
        return move(ws, "CREDIT", amount, refType, refId, note, by);
    }

    public BigDecimal debit(long ws, BigDecimal amount, String refType, Long refId, String note, Long by) {
        return move(ws, "DEBIT", amount, refType, refId, note, by);
    }

    public BigDecimal refund(long ws, BigDecimal amount, String refType, Long refId, String note) {
        return move(ws, "REFUND", amount, refType, refId, note, null);
    }

    /** Signed manual adjustment (super admin). */
    public BigDecimal adjust(long ws, BigDecimal signed, String note, Long by) {
        return move(ws, "ADJUSTMENT", signed, "MANUAL", null, note, by);
    }

    private BigDecimal move(long ws, String type, BigDecimal amount, String refType, Long refId, String note, Long by) {
        if (amount.signum() == 0) return balance(ws);
        return db.tx(() -> {
            BigDecimal bal = db.scalar("select wallet_balance from workspaces where id=:id for update", BigDecimal.class, "id", ws);
            if (bal == null) throw ApiException.notFound("Workspace");
            BigDecimal delta = switch (type) {
                case "CREDIT", "REFUND" -> amount.abs();
                case "DEBIT" -> amount.abs().negate();
                default -> amount; // ADJUSTMENT keeps its sign
            };
            BigDecimal after = bal.add(delta).setScale(4, RoundingMode.HALF_UP);
            if (after.signum() < 0) throw ApiException.insufficient("Your wallet balance is too low. Please top up.");
            db.update("update workspaces set wallet_balance=:b where id=:id", "b", after, "id", ws);
            db.insert("insert into wallet_transactions (workspace_id,type,amount,balance_after,reference_type,reference_id,note,created_by) " +
                            "values (:ws,:t,:a,:ba,:rt,:rid,:n,:by)",
                    "ws", ws, "t", type, "a", delta.abs().setScale(4, RoundingMode.HALF_UP), "ba", after, "rt", refType, "rid", refId,
                    "n", note == null ? null : (note.length() > 255 ? note.substring(0, 255) : note), "by", by);
            if (delta.signum() < 0 && after.compareTo(LOW) < 0) {
                notifications.notifyOnce(ws, "LOW_WALLET", 24, "Low wallet balance",
                        "Your message wallet has " + Money.format(after) + " left. Top up to keep campaigns running.", "/settings/billing");
            }
            return after;
        });
    }

    /** NFR-08: balance column must equal the ledger sum. Returns the difference. */
    public BigDecimal ledgerDifference(long ws) {
        BigDecimal ledger = db.scalar("select coalesce(sum(case when type in ('CREDIT','REFUND') then amount when type='DEBIT' then -amount " +
                "else 0 end),0) from wallet_transactions where workspace_id=:ws and type<>'ADJUSTMENT'", BigDecimal.class, "ws", ws);
        // ADJUSTMENT rows store |delta| only, so their sign is recovered from balance_after chain
        BigDecimal adj = adjustmentNet(ws);
        return balance(ws).subtract(ledger.add(adj));
    }

    private BigDecimal adjustmentNet(long ws) {
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal prev = BigDecimal.ZERO;
        for (Row r : db.rows("select type, amount, balance_after from wallet_transactions where workspace_id=:ws order by id", "ws", ws)) {
            BigDecimal after = r.d("balance_after");
            if ("ADJUSTMENT".equals(r.s("type"))) net = net.add(after.subtract(prev));
            prev = after;
        }
        return net;
    }
}
