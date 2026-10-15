package com.marketingpanel.affiliate;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.auth.AuthUser;
import com.marketingpanel.common.*;
import com.marketingpanel.jobs.JobHandler;
import com.marketingpanel.payout.EarningsLedger;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** P33 Conversions & postback logs: approve/reject during the hold, reversal by Finance within 30 days, hold release, outbound postbacks. */
@Service
public class ConversionService implements JobHandler {
    private static final List<String> REASONS = List.of("Fraud", "Duplicate user", "Test install", "Policy breach", "Other");
    private final Db db;
    private final EarningsLedger ledger;
    private final WalletService wallet;
    private final AuditService audit;
    private final NotificationService notifications;
    private final RestClient http;

    public ConversionService(Db db, EarningsLedger ledger, WalletService wallet, AuditService audit, NotificationService notifications) {
        this.db = db;
        this.ledger = ledger;
        this.wallet = wallet;
        this.audit = audit;
        this.notifications = notifications;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(5000);
        this.http = RestClient.builder().requestFactory(f).build();
    }

    public record Action(String action, String reason) {}

    public record Bulk(List<Long> ids, String action, String reason) {}

    public Map<String, Object> list(AuthUser me, Long offerId, String status, String promoterCode, LocalDate from, LocalDate to, Long cursor, int size) {
        if (from != null && to != null && java.time.temporal.ChronoUnit.DAYS.between(from, to) > 93) throw ApiException.invalid("from", "conv.range", "Choose a range of up to 93 days.");
        size = Math.min(Math.max(size, 1), 100);
        StringBuilder sb = new StringBuilder("select v.*, o.name as offer_name, p.referral_code from conversions v join offers o on o.id=v.offer_id left join promoters p on p.id=v.promoter_id where 1=1");
        List<Object> kv = new ArrayList<>();
        if (!me.isAdmin()) {
            sb.append(" and o.workspace_id=:ws");
            kv.add("ws");
            kv.add(me.workspaceId());
        }
        if (offerId != null) {
            sb.append(" and v.offer_id=:o");
            kv.add("o");
            kv.add(offerId);
        }
        if (status != null && !status.isBlank()) {
            sb.append(" and v.status=:s");
            kv.add("s");
            kv.add(status);
        }
        if (promoterCode != null && !promoterCode.isBlank()) {
            sb.append(" and p.referral_code=:pc");
            kv.add("pc");
            kv.add(promoterCode.toUpperCase());
        }
        if (from != null) {
            sb.append(" and v.created_at >= :f");
            kv.add("f");
            kv.add(Time.startOfIstDayUtc(from));
        }
        if (to != null) {
            sb.append(" and v.created_at < :t");
            kv.add("t");
            kv.add(Time.startOfIstDayUtc(to.plusDays(1)));
        }
        if (cursor != null) {
            sb.append(" and v.id < :c");
            kv.add("c");
            kv.add(cursor);
        }
        sb.append(" order by v.id desc limit ").append(size + 1);
        List<Row> rows = db.rows(sb.toString(), kv.toArray());
        boolean more = rows.size() > size;
        if (more) rows = rows.subList(0, size);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", Row.api(rows));
        out.put("nextCursor", more ? rows.get(rows.size() - 1).l("id") : null);
        return out;
    }

    public List<Map<String, Object>> logs(AuthUser me, Long offerId, String result, LocalDate from, LocalDate to) {
        if (from != null && to != null && java.time.temporal.ChronoUnit.DAYS.between(from, to) > 93) throw ApiException.invalid("from", "conv.range", "Choose a range of up to 93 days.");
        StringBuilder sb = new StringBuilder("select l.* from postback_logs l left join offers o on o.id=l.offer_id where 1=1");
        List<Object> kv = new ArrayList<>();
        if (!me.isAdmin()) {
            sb.append(" and o.workspace_id=:ws");
            kv.add("ws");
            kv.add(me.workspaceId());
        }
        if (offerId != null) {
            sb.append(" and l.offer_id=:o");
            kv.add("o");
            kv.add(offerId);
        }
        if (result != null && !result.isBlank()) {
            sb.append(" and l.result=:r");
            kv.add("r");
            kv.add(result);
        }
        if (from != null) {
            sb.append(" and l.created_at >= :f");
            kv.add("f");
            kv.add(Time.startOfIstDayUtc(from));
        }
        if (to != null) {
            sb.append(" and l.created_at < :t");
            kv.add("t");
            kv.add(Time.startOfIstDayUtc(to.plusDays(1)));
        }
        sb.append(" order by l.id desc limit 200");
        return db.list(sb.toString(), kv.toArray());
    }

    private Row load(AuthUser me, long id) {
        Row v = db.row("select v.*, o.workspace_id, o.name as offer_name from conversions v join offers o on o.id=v.offer_id where v.id=:id", "id", id).orElseThrow(() -> ApiException.notFound("Conversion"));
        if (!me.isAdmin() && v.l("workspace_id") != me.workspaceId()) throw ApiException.notFound("Conversion");
        return v;
    }

    private String reason(String r, boolean required) {
        if (r == null || r.isBlank()) {
            if (required) throw ApiException.invalid("reason", "conv.reason", "Give a reason for rejecting.");
            return null;
        }
        String c = Norm.plain(r);
        if (!REASONS.contains(c) && (c.length() < 10 || c.length() > 255)) throw ApiException.invalid("reason", "conv.reason", "Give a reason for rejecting.");
        return c;
    }

    public Map<String, Object> act(AuthUser me, long id, Action a) {
        Row v = load(me, id);
        apply(me, v, a.action(), a.reason());
        return db.one("select * from conversions where id=:id", "id", id).orElseThrow();
    }

    public Map<String, Object> bulk(AuthUser me, Bulk b) {
        if (b.ids() == null || b.ids().isEmpty() || b.ids().size() > 1000) throw ApiException.invalid("ids", "conv.bulk", "Select 1 to 1,000 conversions.");
        int ok = 0, skipped = 0;
        for (long id : new LinkedHashSet<>(b.ids())) {
            try {
                apply(me, load(me, id), b.action(), b.reason());
                ok++;
            } catch (ApiException e) {
                skipped++;
            }
        }
        audit.log("CONVERSIONS_BULK_" + b.action().toUpperCase(), "conversions", null, null, Map.of("done", ok, "skipped", skipped));
        return Map.of("done", ok, "skipped", skipped);
    }

    private void apply(AuthUser me, Row v, String action, String reasonRaw) {
        long id = v.l("id");
        String status = v.s("status");
        switch (action) {
            case "approve" -> {
                if (!"PENDING".equals(status)) throw ApiException.state("Only pending conversions can be approved.");
                db.tx(() -> {
                    int n = db.update("update conversions set status='APPROVED', approved_at=:now where id=:id and status='PENDING'", "now", Time.now(), "id", id);
                    if (n > 0 && v.lo("promoter_id") != null) ledger.releaseHold(v.l("promoter_id"), v.d("promoter_payout"), id);
                });
            }
            case "reject" -> {
                if (!"PENDING".equals(status)) throw ApiException.state("A conversion can be rejected only while it is pending (inside the hold).");
                String reason = reason(reasonRaw, true);
                db.tx(() -> {
                    int n = db.update("update conversions set status='REJECTED', reject_reason=:r where id=:id and status='PENDING'", "r", reason, "id", id);
                    if (n == 0) return;
                    if (v.lo("promoter_id") != null) ledger.post(v.l("promoter_id"), "REVERSAL", v.d("promoter_payout").negate(), "PENDING", id, null, "Rejected: " + reason);
                    wallet.refund(v.l("workspace_id"), v.d("advertiser_price"), "CONVERSION", id, "Refund: conversion rejected");
                });
            }
            case "reverse" -> {
                if (!me.isAdmin() || !List.of("SUPER_ADMIN", "FINANCE").contains(me.adminRole())) throw ApiException.forbidden("Only Finance or a Super Admin can reverse an approved conversion.");
                if (!"APPROVED".equals(status) && !"PAID".equals(status)) throw ApiException.state("Only approved conversions can be reversed.");
                if (v.t("approved_at") == null || v.t("approved_at").isBefore(Time.now().minusDays(30))) throw ApiException.state("Reversal is allowed within 30 days of approval only.");
                String reason = reason(reasonRaw, true);
                db.tx(() -> {
                    int n = db.update("update conversions set status='REVERSED', reject_reason=:r where id=:id and status in ('APPROVED','PAID')", "r", reason, "id", id);
                    if (n == 0) return;
                    if (v.lo("promoter_id") != null) ledger.post(v.l("promoter_id"), "REVERSAL", v.d("promoter_payout").negate(), "AVAILABLE", id, null, "Reversed: " + reason);
                    wallet.refund(v.l("workspace_id"), v.d("advertiser_price"), "CONVERSION", id, "Refund: conversion reversed");
                });
            }
            default -> throw ApiException.badRequest("Unknown action.");
        }
        audit.log(v.l("workspace_id"), me.userId(), "CONVERSION_" + action.toUpperCase(), "conversions", id, Map.of("status", status), reasonRaw == null ? null : Map.of("reason", reasonRaw));
    }

    /** After the hold period conversions approve themselves; the promoter's pending amount becomes available. */
    public int releaseHolds() {
        int n = 0;
        for (Row v : db.rows("select * from conversions where status='PENDING' and hold_until <= :now order by id limit 500", "now", Time.now())) {
            try {
                db.tx(() -> {
                    int u = db.update("update conversions set status='APPROVED', approved_at=:now where id=:id and status='PENDING'", "now", Time.now(), "id", v.l("id"));
                    if (u > 0 && v.lo("promoter_id") != null) ledger.releaseHold(v.l("promoter_id"), v.d("promoter_payout"), v.l("id"));
                });
                n++;
            } catch (Exception e) {
                // retried on the next run
            }
        }
        return n;
    }

    // ------------------------------------------------- outbound postback job

    @Override
    public String type() {
        return "POSTBACK_OUT";
    }

    @Override
    public void handle(JsonNode p) {
        long id = p.get("conversionId").asLong();
        Row v = db.row("select v.*, o.event_type as offer_event, pr.postback_url from conversions v join offers o on o.id=v.offer_id join promoters pr on pr.id=v.promoter_id where v.id=:id", "id", id).orElse(null);
        if (v == null || v.s("postback_url") == null) return;
        String tpl = v.s("postback_url");
        Row click = db.row("select sub1, sub2 from tracking_clicks where click_id=:c", "c", v.s("click_id")).orElse(null);
        String url = tpl.replace("{click_id}", enc(v.s("click_id"))).replace("{sub1}", enc(click == null ? "" : click.s("sub1")))
                .replace("{payout}", v.d("promoter_payout").toPlainString()).replace("{event}", enc(v.s("event_type"))).replace("{txn_id}", enc(v.s("external_txn_id"))).replace("{status}", v.s("status"));
        Integer status = null;
        String result;
        try {
            UrlGuard.assertSafeToFetch(url);
            status = http.get().uri(java.net.URI.create(url)).retrieve().toBodilessEntity().getStatusCode().value();
            result = "SENT";
        } catch (Exception e) {
            result = "SEND_FAILED";
        }
        db.insertInto("postback_logs", "direction", "OUT", "offer_id", v.l("offer_id"), "conversion_id", id, "url", url.length() > 2000 ? url.substring(0, 2000) : url,
                "result", result, "http_status", status, "attempt", 1);
        if ("SEND_FAILED".equals(result)) throw new IllegalStateException("Outbound postback failed"); // retried with backoff
    }

    private static String enc(String s) {
        return s == null ? "" : URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
