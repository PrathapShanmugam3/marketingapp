package com.marketingpanel.commerce;

import com.marketingpanel.common.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/** Discount coupons: percent or flat, with minimum order, caps, validity window and per-customer limits. */
@Service
public class CouponService {
    private final Db db;
    private final PlanService plans;
    private final AuditService audit;

    public CouponService(Db db, PlanService plans, AuditService audit) {
        this.db = db;
        this.plans = plans;
        this.audit = audit;
    }

    public record Request(String code, String description, String discountType, BigDecimal discountValue, BigDecimal maxDiscount, BigDecimal minOrder, Integer maxRedemptions,
                          Integer perContactLimit, String validFrom, String validUntil, Boolean active) {}

    public record Redeem(String code, Long contactId, BigDecimal orderAmount) {}

    public List<Map<String, Object>> list(long ws) {
        return db.list("select * from coupons where workspace_id=:ws order by id desc limit 500", "ws", ws);
    }

    private Map<String, Object> clean(Request r) {
        List<FieldErr> errs = new ArrayList<>();
        String code = r.code() == null ? "" : r.code().trim().toUpperCase();
        if (!code.matches("^[A-Z0-9]{4,20}$")) errs.add(new FieldErr("code", "coupon.code", "Use 4 to 20 letters or digits."));
        String type = r.discountType();
        if (!List.of("PERCENT", "FLAT").contains(type)) errs.add(new FieldErr("discountType", "coupon.type", "Choose percent or flat."));
        BigDecimal v = r.discountValue();
        if (v == null || v.signum() <= 0 || ("PERCENT".equals(type) && v.compareTo(new BigDecimal("100")) > 0) || v.compareTo(new BigDecimal("1000000")) > 0)
            errs.add(new FieldErr("discountValue", "coupon.value", "PERCENT".equals(type) ? "Enter 1 to 100." : "Enter an amount above zero."));
        LocalDateTime from = parse(r.validFrom(), "validFrom", errs);
        LocalDateTime until = parse(r.validUntil(), "validUntil", errs);
        if (from != null && until != null && !until.isAfter(from)) errs.add(new FieldErr("validUntil", "coupon.range", "The end must be after the start."));
        if (r.maxRedemptions() != null && r.maxRedemptions() < 1) errs.add(new FieldErr("maxRedemptions", "coupon.max", "Enter 1 or more."));
        if (r.perContactLimit() != null && (r.perContactLimit() < 1 || r.perContactLimit() > 100)) errs.add(new FieldErr("perContactLimit", "coupon.per", "Enter 1 to 100."));
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("description", Norm.plain(r.description()));
        m.put("discount_type", type);
        m.put("discount_value", v);
        m.put("max_discount", "PERCENT".equals(type) ? r.maxDiscount() : null);
        m.put("min_order", r.minOrder() == null ? BigDecimal.ZERO : r.minOrder());
        m.put("max_redemptions", r.maxRedemptions());
        m.put("per_contact_limit", r.perContactLimit() == null ? 1 : r.perContactLimit());
        m.put("valid_from", from);
        m.put("valid_until", until);
        m.put("active", r.active() == null || r.active());
        return m;
    }

    private LocalDateTime parse(String s, String field, List<FieldErr> errs) {
        if (s == null || s.isBlank()) return null;
        try {
            return Time.parseUtc(s);
        } catch (Exception e) {
            errs.add(new FieldErr(field, "coupon.date", "Choose a valid date and time."));
            return null;
        }
    }

    public Map<String, Object> create(long ws, Request r) {
        plans.requireFeature(ws, "loyalty");
        Map<String, Object> m = clean(r);
        if (db.exists("select 1 from coupons where workspace_id=:ws and code=:c", "ws", ws, "c", m.get("code"))) throw ApiException.duplicate("code", "coupon.exists", "You already have a coupon with this code.");
        Object[] kv = new Object[m.size() * 2 + 2];
        kv[0] = "workspace_id";
        kv[1] = ws;
        int i = 2;
        for (var e : m.entrySet()) {
            kv[i++] = e.getKey();
            kv[i++] = e.getValue();
        }
        long id = db.insertInto("coupons", kv);
        audit.log("COUPON_CREATED", "coupons", id);
        return db.one("select * from coupons where id=:id", "id", id).orElseThrow();
    }

    public Map<String, Object> update(long ws, long id, Request r) {
        Row c = db.row("select * from coupons where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Coupon"));
        Map<String, Object> m = clean(r);
        if (!m.get("code").equals(c.s("code"))) {
            if (c.i("redeemed_count") > 0) throw ApiException.state("The code of a redeemed coupon cannot be changed.");
            if (db.exists("select 1 from coupons where workspace_id=:ws and code=:c and id<>:id", "ws", ws, "c", m.get("code"), "id", id)) throw ApiException.duplicate("code", "coupon.exists", "You already have a coupon with this code.");
        }
        db.updateTable("coupons", "id", id, m, "workspace_id", ws);
        return db.one("select * from coupons where id=:id", "id", id).orElseThrow();
    }

    public void delete(long ws, long id) {
        Row c = db.row("select * from coupons where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Coupon"));
        if (c.i("redeemed_count") > 0) throw ApiException.state("A redeemed coupon cannot be deleted. Turn it off instead.");
        db.update("delete from coupons where id=:id", "id", id);
    }

    /** Checks a code for an order without using it. */
    public Map<String, Object> validate(long ws, Redeem r) {
        return evaluate(ws, r, false);
    }

    /** Atomically checks limits under a row lock and records the redemption. */
    public Map<String, Object> redeem(long ws, Redeem r) {
        return db.tx(() -> evaluate(ws, r, true));
    }

    private Map<String, Object> evaluate(long ws, Redeem r, boolean commit) {
        String code = r.code() == null ? "" : r.code().trim().toUpperCase();
        Row c = db.row("select * from coupons where workspace_id=:ws and code=:c" + (commit ? " for update" : ""), "ws", ws, "c", code)
                .orElseThrow(() -> ApiException.invalid("code", "coupon.unknown", "This coupon code is not valid."));
        BigDecimal amount = r.orderAmount();
        if (amount == null || amount.signum() <= 0) throw ApiException.invalid("orderAmount", "coupon.amount", "Enter the order amount.");
        LocalDateTime now = Time.now();
        if (!c.b("active")) throw ApiException.invalid("code", "coupon.off", "This coupon is switched off.");
        if (c.t("valid_from") != null && now.isBefore(c.t("valid_from"))) throw ApiException.invalid("code", "coupon.early", "This coupon is not active yet.");
        if (c.t("valid_until") != null && now.isAfter(c.t("valid_until"))) throw ApiException.invalid("code", "coupon.expired", "This coupon has expired.");
        if (c.lo("max_redemptions") != null && c.i("redeemed_count") >= c.l("max_redemptions")) throw ApiException.invalid("code", "coupon.used", "This coupon has been fully used.");
        if (amount.compareTo(c.d("min_order")) < 0) throw ApiException.invalid("orderAmount", "coupon.min", "The minimum order for this coupon is " + Money.format(c.d("min_order")) + ".");
        if (r.contactId() != null) {
            if (!db.exists("select 1 from contacts where id=:id and workspace_id=:ws and deleted_at is null", "id", r.contactId(), "ws", ws)) throw ApiException.notFound("Contact");
            if (db.count("select count(*) from coupon_redemptions where coupon_id=:c and contact_id=:k", "c", c.l("id"), "k", r.contactId()) >= c.i("per_contact_limit"))
                throw ApiException.invalid("code", "coupon.per", "This customer has already used this coupon.");
        }
        BigDecimal discount = "PERCENT".equals(c.s("discount_type")) ? amount.multiply(c.d("discount_value")).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP) : c.d("discount_value");
        if (c.d("max_discount") != null && discount.compareTo(c.d("max_discount")) > 0) discount = c.d("max_discount");
        if (discount.compareTo(amount) > 0) discount = amount;
        discount = discount.setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("discount", discount);
        out.put("payable", amount.subtract(discount));
        if (commit) {
            db.insertInto("coupon_redemptions", "workspace_id", ws, "coupon_id", c.l("id"), "contact_id", r.contactId(), "order_amount", amount, "discount_amount", discount);
            db.update("update coupons set redeemed_count=redeemed_count+1 where id=:id", "id", c.l("id"));
        }
        out.put("redeemed", commit);
        return out;
    }

    public Map<String, Object> stats(long ws) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("redemptions", db.count("select count(*) from coupon_redemptions where workspace_id=:ws", "ws", ws));
        m.put("discountGiven", db.scalar("select coalesce(sum(discount_amount),0) from coupon_redemptions where workspace_id=:ws", BigDecimal.class, "ws", ws));
        m.put("orderValue", db.scalar("select coalesce(sum(order_amount),0) from coupon_redemptions where workspace_id=:ws", BigDecimal.class, "ws", ws));
        return m;
    }
}
