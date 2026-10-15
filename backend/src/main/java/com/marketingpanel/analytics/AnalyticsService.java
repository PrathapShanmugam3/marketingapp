package com.marketingpanel.analytics;

import com.marketingpanel.ads.AdsService;
import com.marketingpanel.common.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** One cross-channel view: reach by channel, leads and revenue by source, ad efficiency, retention. All figures are for the workspace and an IST date range. */
@Service
public class AnalyticsService {
    private static final String DAY = "date(convert_tz(%s,'+00:00','+05:30'))";
    private final Db db;
    private final AdsService ads;

    public AnalyticsService(Db db, AdsService ads) {
        this.db = db;
        this.ads = ads;
    }

    private long n(String sql, Object... kv) {
        return db.count(sql, kv);
    }

    private BigDecimal sum(String sql, Object... kv) {
        BigDecimal b = db.scalar(sql, BigDecimal.class, kv);
        return b == null ? BigDecimal.ZERO : b;
    }

    public Map<String, Object> overview(long ws, LocalDate from, LocalDate to) {
        if (to == null) to = Time.todayIst();
        if (from == null) from = to.minusDays(29);
        if (from.isAfter(to)) throw ApiException.invalid("from", "analytics.range", "The start date must be before the end date.");
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > 366) throw ApiException.invalid("from", "analytics.range", "Choose a range of up to one year.");
        LocalDateTime f = Time.startOfIstDayUtc(from);
        LocalDateTime t = Time.startOfIstDayUtc(to.plusDays(1));
        Object[] w = {"ws", ws, "f", f, "t", t};
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());

        // ---- reach by channel
        Map<String, Object> ch = new LinkedHashMap<>();
        long waSent = n("select count(*) from messages where workspace_id=:ws and direction='OUT' and created_at>=:f and created_at<:t", w);
        Map<String, Object> wa = new LinkedHashMap<>();
        wa.put("sent", waSent);
        wa.put("delivered", n("select count(*) from messages where workspace_id=:ws and direction='OUT' and status in ('DELIVERED','READ') and created_at>=:f and created_at<:t", w));
        wa.put("read", n("select count(*) from messages where workspace_id=:ws and direction='OUT' and status='READ' and created_at>=:f and created_at<:t", w));
        wa.put("replies", n("select count(*) from messages where workspace_id=:ws and direction='IN' and created_at>=:f and created_at<:t", w));
        ch.put("whatsapp", wa);
        for (String c : List.of("EMAIL", "SMS", "PUSH")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("broadcasts", n("select count(*) from broadcasts where workspace_id=:ws and channel='" + c + "' and started_at>=:f and started_at<:t", w));
            m.put("sent", n("select coalesce(sum(sent_count),0) from broadcasts where workspace_id=:ws and channel='" + c + "' and started_at>=:f and started_at<:t", w));
            m.put("failed", n("select coalesce(sum(failed_count),0) from broadcasts where workspace_id=:ws and channel='" + c + "' and started_at>=:f and started_at<:t", w));
            m.put("unsubscribed", n("select coalesce(sum(unsub_count),0) from broadcasts where workspace_id=:ws and channel='" + c + "' and started_at>=:f and started_at<:t", w));
            m.put("spend", sum("select coalesce(sum(cost),0) from broadcasts where workspace_id=:ws and channel='" + c + "' and started_at>=:f and started_at<:t", w));
            ch.put(c.toLowerCase(), m);
        }
        ch.put("social", Map.of("posts", n("select count(*) from social_posts where workspace_id=:ws and status in ('PUBLISHED','PARTIAL') and coalesce(scheduled_at,created_at)>=:f and coalesce(scheduled_at,created_at)<:t", w),
                "dmsReceived", n("select count(*) from dm_messages m join dm_threads th on th.id=m.thread_id where th.workspace_id=:ws and m.direction='IN' and m.created_at>=:f and m.created_at<:t", w)));
        out.put("channels", ch);

        // ---- leads and revenue
        long leads = n("select count(*) from leads where workspace_id=:ws and deleted_at is null and created_at>=:f and created_at<:t", w);
        long won = n("select count(*) from leads where workspace_id=:ws and deleted_at is null and status='WON' and created_at>=:f and created_at<:t", w);
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("total", leads);
        l.put("won", won);
        l.put("lost", n("select count(*) from leads where workspace_id=:ws and deleted_at is null and status='LOST' and created_at>=:f and created_at<:t", w));
        l.put("winRate", leads == 0 ? 0 : Math.round(won * 1000.0 / leads) / 10.0);
        l.put("wonValue", sum("select coalesce(sum(expected_value),0) from leads where workspace_id=:ws and deleted_at is null and status='WON' and created_at>=:f and created_at<:t", w));
        l.put("pipelineValue", sum("select coalesce(sum(expected_value),0) from leads where workspace_id=:ws and deleted_at is null and status in ('NEW','CONTACTED','INTERESTED') and created_at>=:f and created_at<:t", w));
        l.put("fromForms", n("select count(*) from leads where workspace_id=:ws and deleted_at is null and lead_form_id is not null and created_at>=:f and created_at<:t", w));
        l.put("bySource", db.list("select source, count(*) as leads, sum(status='WON') as won, coalesce(sum(case when status='WON' then expected_value end),0) as won_value from leads where workspace_id=:ws and deleted_at is null and created_at>=:f and created_at<:t group by source order by leads desc", w));
        l.put("byStatus", db.list("select status, count(*) as n from leads where workspace_id=:ws and deleted_at is null and created_at>=:f and created_at<:t group by status", w));
        out.put("leads", l);

        // ---- traffic
        Map<String, Object> traffic = new LinkedHashMap<>();
        traffic.put("linkClicks", n("select count(*) from link_clicks c join short_links s on s.id=c.short_link_id where s.workspace_id=:ws and c.is_bot=0 and c.clicked_at>=:f and c.clicked_at<:t", w));
        traffic.put("blogViews", n("select coalesce(sum(view_count),0) from content_items where workspace_id=:ws and type='BLOG'", "ws", ws));
        traffic.put("topLinks", db.list("select s.code, coalesce(s.destination_url, a.name) as destination, count(*) as clicks from link_clicks c join short_links s on s.id=c.short_link_id left join apps a on a.id=s.app_id " +
                "where s.workspace_id=:ws and c.is_bot=0 and c.clicked_at>=:f and c.clicked_at<:t group by s.id order by clicks desc limit 5", w));
        out.put("traffic", traffic);

        // ---- ads and blended efficiency
        Map<String, Object> adSummary = ads.summary(ws, from, to);
        out.put("ads", adSummary);
        BigDecimal adSpend = Money.bd(adSummary.get("spend"));
        BigDecimal msgSpend = sum("select coalesce(sum(cost),0) from broadcasts where workspace_id=:ws and started_at>=:f and started_at<:t", w);
        Map<String, Object> blended = new LinkedHashMap<>();
        blended.put("adSpend", adSpend);
        blended.put("smsSpend", msgSpend);
        blended.put("costPerLead", leads == 0 ? null : adSpend.divide(BigDecimal.valueOf(leads), 2, RoundingMode.HALF_UP));
        BigDecimal wonValue = (BigDecimal) l.get("wonValue");
        blended.put("revenueToSpend", adSpend.signum() == 0 ? null : wonValue.divide(adSpend, 2, RoundingMode.HALF_UP));
        out.put("blended", blended);

        // ---- retention
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("contacts", n("select count(*) from contacts where workspace_id=:ws and deleted_at is null", "ws", ws));
        ret.put("optedIn", n("select count(*) from contacts where workspace_id=:ws and deleted_at is null and opt_in_status='OPTED_IN'", "ws", ws));
        ret.put("newContacts", n("select count(*) from contacts where workspace_id=:ws and deleted_at is null and created_at>=:f and created_at<:t", w));
        ret.put("couponRedemptions", n("select count(*) from coupon_redemptions where workspace_id=:ws and created_at>=:f and created_at<:t", w));
        ret.put("couponDiscount", sum("select coalesce(sum(discount_amount),0) from coupon_redemptions where workspace_id=:ws and created_at>=:f and created_at<:t", w));
        ret.put("couponOrderValue", sum("select coalesce(sum(order_amount),0) from coupon_redemptions where workspace_id=:ws and created_at>=:f and created_at<:t", w));
        ret.put("pointsIssued", n("select coalesce(sum(points),0) from loyalty_ledger where workspace_id=:ws and points>0 and created_at>=:f and created_at<:t", w));
        ret.put("pointsRedeemed", n("select coalesce(-sum(points),0) from loyalty_ledger where workspace_id=:ws and points<0 and reason='REDEEM' and created_at>=:f and created_at<:t", w));
        ret.put("referrals", n("select count(*) from referrals where workspace_id=:ws and created_at>=:f and created_at<:t", w));
        Map<String, Object> review = db.one("select rating, review_count from review_stats where workspace_id=:ws order by captured_on desc limit 1", "ws", ws).orElse(null);
        ret.put("googleRating", review);
        out.put("retention", ret);

        // ---- daily series (for charts)
        Map<LocalDate, Map<String, Object>> days = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", d.toString());
            row.put("whatsapp", 0);
            row.put("email", 0);
            row.put("sms", 0);
            row.put("leads", 0);
            row.put("clicks", 0);
            row.put("adSpend", 0);
            days.put(d, row);
        }
        fill(days, "whatsapp", "select " + DAY.formatted("created_at") + " as d, count(*) as v from messages where workspace_id=:ws and direction='OUT' and created_at>=:f and created_at<:t group by d", w);
        fill(days, "email", "select " + DAY.formatted("r.sent_at") + " as d, count(*) as v from broadcast_recipients r join broadcasts b on b.id=r.broadcast_id where b.workspace_id=:ws and b.channel='EMAIL' and r.status='SENT' and r.sent_at>=:f and r.sent_at<:t group by d", w);
        fill(days, "sms", "select " + DAY.formatted("r.sent_at") + " as d, count(*) as v from broadcast_recipients r join broadcasts b on b.id=r.broadcast_id where b.workspace_id=:ws and b.channel='SMS' and r.status='SENT' and r.sent_at>=:f and r.sent_at<:t group by d", w);
        fill(days, "leads", "select " + DAY.formatted("created_at") + " as d, count(*) as v from leads where workspace_id=:ws and deleted_at is null and created_at>=:f and created_at<:t group by d", w);
        fill(days, "clicks", "select " + DAY.formatted("c.clicked_at") + " as d, count(*) as v from link_clicks c join short_links s on s.id=c.short_link_id where s.workspace_id=:ws and c.is_bot=0 and c.clicked_at>=:f and c.clicked_at<:t group by d", w);
        for (Row r : db.rows("select m.day as d, sum(m.spend) as v from ad_metrics m join ad_campaigns a on a.id=m.ad_campaign_id where a.workspace_id=:ws and m.day between :fd and :td group by m.day", "ws", ws, "fd", from, "td", to)) {
            Map<String, Object> row = days.get(r.date("d"));
            if (row != null) row.put("adSpend", r.d("v"));
        }
        out.put("series", new ArrayList<>(days.values()));
        return out;
    }

    private void fill(Map<LocalDate, Map<String, Object>> days, String key, String sql, Object[] kv) {
        for (Row r : db.rows(sql, kv)) {
            Map<String, Object> row = days.get(r.date("d"));
            if (row != null) row.put(key, r.l("v"));
        }
    }
}
