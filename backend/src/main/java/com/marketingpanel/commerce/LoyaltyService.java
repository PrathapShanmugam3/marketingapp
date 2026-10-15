package com.marketingpanel.commerce;

import com.marketingpanel.common.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Points for purchases, redemption at a fixed rupee value per point, tiers, and a refer-a-friend bonus for both sides. */
@Service
public class LoyaltyService {
    private final Db db;
    private final PlanService plans;
    private final AuditService audit;

    public LoyaltyService(Db db, PlanService plans, AuditService audit) {
        this.db = db;
        this.plans = plans;
        this.audit = audit;
    }

    public record Settings(Boolean enabled, Integer pointsPer100, BigDecimal rupeesPerPoint, Integer referralBonus, Integer refereeBonus, Integer silverAt, Integer goldAt) {}

    public Map<String, Object> settings(long ws) {
        ensureSettings(ws);
        return db.one("select * from loyalty_settings where workspace_id=:ws", "ws", ws).orElseThrow();
    }

    private void ensureSettings(long ws) {
        db.update("insert ignore into loyalty_settings (workspace_id) values (:ws)", "ws", ws);
    }

    public Map<String, Object> saveSettings(long ws, Settings s) {
        plans.requireFeature(ws, "loyalty");
        List<FieldErr> errs = new ArrayList<>();
        if (s.pointsPer100() == null || s.pointsPer100() < 0 || s.pointsPer100() > 1000) errs.add(new FieldErr("pointsPer100", "loy.points", "Enter 0 to 1000."));
        if (s.rupeesPerPoint() == null || s.rupeesPerPoint().signum() <= 0 || s.rupeesPerPoint().compareTo(new BigDecimal("1000")) > 0) errs.add(new FieldErr("rupeesPerPoint", "loy.value", "Enter a value above zero."));
        if (s.referralBonus() == null || s.referralBonus() < 0 || s.referralBonus() > 100000) errs.add(new FieldErr("referralBonus", "loy.ref", "Enter 0 to 100000."));
        if (s.refereeBonus() == null || s.refereeBonus() < 0 || s.refereeBonus() > 100000) errs.add(new FieldErr("refereeBonus", "loy.ref", "Enter 0 to 100000."));
        if (s.silverAt() == null || s.goldAt() == null || s.silverAt() < 1 || s.goldAt() <= s.silverAt()) errs.add(new FieldErr("goldAt", "loy.tiers", "Gold must need more points than Silver."));
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        ensureSettings(ws);
        db.update("update loyalty_settings set enabled=:e, points_per_100=:p, rupees_per_point=:r, referral_bonus=:rb, referee_bonus=:fb, silver_at=:s, gold_at=:g where workspace_id=:ws",
                "e", s.enabled() == null || s.enabled(), "p", s.pointsPer100(), "r", s.rupeesPerPoint(), "rb", s.referralBonus(), "fb", s.refereeBonus(), "s", s.silverAt(), "g", s.goldAt(), "ws", ws);
        return settings(ws);
    }

    private Row cfg(long ws) {
        ensureSettings(ws);
        return db.row("select * from loyalty_settings where workspace_id=:ws", "ws", ws).orElseThrow();
    }

    static String tier(Row cfg, int lifetime) {
        return lifetime >= cfg.i("gold_at") ? "GOLD" : lifetime >= cfg.i("silver_at") ? "SILVER" : "MEMBER";
    }

    private Row account(long ws, long contactId, boolean lock) {
        if (!db.exists("select 1 from contacts where id=:id and workspace_id=:ws and deleted_at is null", "id", contactId, "ws", ws)) throw ApiException.notFound("Contact");
        String code;
        do {
            code = Ids.randomUpper(8);
        } while (db.exists("select 1 from loyalty_accounts where referral_code=:c", "c", code));
        db.update("insert ignore into loyalty_accounts (workspace_id, contact_id, referral_code) values (:ws,:c,:code)", "ws", ws, "c", contactId, "code", code);
        return db.row("select * from loyalty_accounts where workspace_id=:ws and contact_id=:c" + (lock ? " for update" : ""), "ws", ws, "c", contactId).orElseThrow();
    }

    public Map<String, Object> get(long ws, long contactId) {
        Row a = account(ws, contactId, false);
        Row c = cfg(ws);
        Map<String, Object> m = a.api();
        m.put("tier", tier(c, a.i("lifetime_points")));
        m.put("value", BigDecimal.valueOf(a.i("points")).multiply(c.d("rupees_per_point")).setScale(2, RoundingMode.HALF_UP));
        m.put("history", db.list("select id, points, balance_after, reason, note, created_at from loyalty_ledger where workspace_id=:ws and contact_id=:c order by id desc limit 50", "ws", ws, "c", contactId));
        return m;
    }

    private Map<String, Object> post(long ws, long contactId, int points, String reason, String note) {
        return db.tx(() -> {
            Row a = account(ws, contactId, true);
            int bal = a.i("points") + points;
            if (bal < 0) throw ApiException.state("Not enough points. Balance: " + a.i("points") + ".");
            db.update("update loyalty_accounts set points=:b, lifetime_points=lifetime_points+:l where workspace_id=:ws and contact_id=:c",
                    "b", bal, "l", Math.max(points, 0), "ws", ws, "c", contactId);
            db.insertInto("loyalty_ledger", "workspace_id", ws, "contact_id", contactId, "points", points, "balance_after", bal, "reason", reason, "note", note);
            return Map.<String, Object>of("points", bal);
        });
    }

    public record Earn(Long contactId, BigDecimal orderAmount, String note) {}

    public Map<String, Object> earn(long ws, Earn e) {
        plans.requireFeature(ws, "loyalty");
        Row c = cfg(ws);
        if (!c.b("enabled")) throw ApiException.state("The loyalty program is switched off.");
        if (e.contactId() == null) throw ApiException.invalid("contactId", "loy.contact", "Choose a customer.");
        if (e.orderAmount() == null || e.orderAmount().signum() <= 0) throw ApiException.invalid("orderAmount", "loy.amount", "Enter the order amount.");
        int pts = e.orderAmount().multiply(BigDecimal.valueOf(c.i("points_per_100"))).divide(new BigDecimal("100"), 0, RoundingMode.FLOOR).intValue();
        Map<String, Object> r = new LinkedHashMap<>(post(ws, e.contactId(), pts, "PURCHASE", Norm.plain(e.note())));
        r.put("earned", pts);
        return r;
    }

    public record Spend(Long contactId, Integer points) {}

    public Map<String, Object> redeem(long ws, Spend s) {
        plans.requireFeature(ws, "loyalty");
        Row c = cfg(ws);
        if (s.contactId() == null || s.points() == null || s.points() < 1) throw ApiException.invalid("points", "loy.points", "Enter the points to use.");
        Map<String, Object> r = new LinkedHashMap<>(post(ws, s.contactId(), -s.points(), "REDEEM", "Redeemed"));
        r.put("discount", BigDecimal.valueOf(s.points()).multiply(c.d("rupees_per_point")).setScale(2, RoundingMode.HALF_UP));
        return r;
    }

    public record Adjust(Long contactId, Integer points, String note) {}

    public Map<String, Object> adjust(long ws, Adjust a) {
        if (a.contactId() == null || a.points() == null || a.points() == 0 || Math.abs(a.points()) > 1_000_000) throw ApiException.invalid("points", "loy.adjust", "Enter the points to add or remove.");
        if (a.note() == null || a.note().isBlank()) throw ApiException.invalid("note", "loy.note", "Give a reason.");
        audit.log("LOYALTY_ADJUSTED", "loyalty_accounts", a.contactId(), null, Map.of("points", a.points()));
        return post(ws, a.contactId(), a.points(), "ADJUST", Norm.plain(a.note()));
    }

    public record Refer(String referralCode, Long referredContactId) {}

    public Map<String, Object> refer(long ws, Refer r) {
        plans.requireFeature(ws, "loyalty");
        Row c = cfg(ws);
        if (r.referredContactId() == null) throw ApiException.invalid("referredContactId", "loy.contact", "Choose the new customer.");
        String code = r.referralCode() == null ? "" : r.referralCode().trim().toUpperCase();
        Row ref = db.row("select * from loyalty_accounts where workspace_id=:ws and referral_code=:c", "ws", ws, "c", code).orElseThrow(() -> ApiException.invalid("referralCode", "loy.code", "This referral code is not valid."));
        if (ref.l("contact_id") == r.referredContactId()) throw ApiException.invalid("referralCode", "loy.self", "A customer cannot refer themselves.");
        if (!db.exists("select 1 from contacts where id=:id and workspace_id=:ws and deleted_at is null", "id", r.referredContactId(), "ws", ws)) throw ApiException.notFound("Contact");
        try {
            db.insertInto("referrals", "workspace_id", ws, "referrer_contact_id", ref.l("contact_id"), "referred_contact_id", r.referredContactId());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw ApiException.duplicate("referredContactId", "loy.dup", "This customer was already referred.");
        }
        if (c.i("referral_bonus") > 0) post(ws, ref.l("contact_id"), c.i("referral_bonus"), "REFERRAL", "Referred a friend");
        if (c.i("referee_bonus") > 0) post(ws, r.referredContactId(), c.i("referee_bonus"), "REFERRED", "Joined with a referral");
        return Map.of("referrerBonus", c.i("referral_bonus"), "refereeBonus", c.i("referee_bonus"));
    }

    public Map<String, Object> leaderboard(long ws) {
        Row c = cfg(ws);
        List<Map<String, Object>> rows = db.list("select a.contact_id, c.full_name, c.phone, a.points, a.lifetime_points, a.referral_code, (select count(*) from referrals r where r.workspace_id=a.workspace_id and r.referrer_contact_id=a.contact_id) as referrals " +
                "from loyalty_accounts a join contacts c on c.id=a.contact_id where a.workspace_id=:ws and c.deleted_at is null order by a.lifetime_points desc limit 100", "ws", ws);
        for (Map<String, Object> m : rows) m.put("tier", tier(c, ((Number) m.get("lifetimePoints")).intValue()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("members", rows);
        out.put("pointsOutstanding", db.scalar("select coalesce(sum(points),0) from loyalty_accounts where workspace_id=:ws", Long.class, "ws", ws));
        return out;
    }
}
