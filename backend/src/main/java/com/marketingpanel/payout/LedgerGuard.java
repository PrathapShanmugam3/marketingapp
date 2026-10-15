package com.marketingpanel.payout;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.marketingpanel.common.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** NFR-08: the nightly ledger check must equal stored balances; any difference blocks payouts and alerts Finance. */
@Component
public class LedgerGuard {
    private static final Logger log = LoggerFactory.getLogger(LedgerGuard.class);
    private final Db db;
    private final EarningsLedger ledger;
    private final WalletService wallet;
    private final AuditService audit;
    private final Cache<String, Boolean> cache = Caffeine.newBuilder().expireAfterWrite(30, TimeUnit.SECONDS).build();

    public LedgerGuard(Db db, EarningsLedger ledger, WalletService wallet, AuditService audit) {
        this.db = db;
        this.ledger = ledger;
        this.wallet = wallet;
        this.audit = audit;
    }

    public boolean blocked() {
        return cache.get("b", k -> "true".equals(db.scalar("select value from system_flags where name='LEDGER_BLOCK'", String.class)));
    }

    public void clear(long adminId) {
        db.update("insert into system_flags (name, value) values ('LEDGER_BLOCK','false') on duplicate key update value='false'");
        cache.invalidateAll();
        audit.log(null, adminId, "LEDGER_BLOCK_CLEARED", "system", null, null, null);
    }

    /** Returns the differences found (empty = consistent). */
    public List<String> check() {
        List<String> diffs = new ArrayList<>(ledger.verify());
        for (Row w : db.rows("select id from workspaces where deleted_at is null")) {
            BigDecimal d = wallet.ledgerDifference(w.l("id"));
            if (d.signum() != 0) diffs.add("workspace " + w.l("id") + " wallet differs by " + d);
        }
        boolean bad = !diffs.isEmpty();
        db.update("insert into system_flags (name, value) values ('LEDGER_BLOCK',:v) on duplicate key update value=:v", "v", bad ? "true" : "false");
        cache.invalidateAll();
        if (bad) {
            log.error("LEDGER MISMATCH: {}", diffs);
            audit.log(null, null, "LEDGER_MISMATCH", "system", null, null, diffs);
        }
        return diffs;
    }
}
