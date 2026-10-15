package com.marketingpanel.ads;

import com.marketingpanel.common.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/** Ad campaign tracker: budgets, daily metrics (entered, CSV-imported or synced) and CTR / CPC / CPA / ROAS reporting. */
@Service
public class AdsService {
    private final Db db;
    private final PlanService plans;
    private final AuditService audit;
    private final AdsClient client;

    public AdsService(Db db, PlanService plans, AuditService audit, AdsClient client) {
        this.db = db;
        this.plans = plans;
        this.audit = audit;
        this.client = client;
    }

    public record Request(String platform, String name, String objective, BigDecimal dailyBudget, BigDecimal totalBudget, String startDate, String endDate, String status,
                          String externalId, String utmCampaign, String notes) {}

    public record Metric(String day, BigDecimal spend, Integer impressions, Integer clicks, Integer conversions, BigDecimal revenue) {}

    private Row load(long ws, long id) {
        return db.row("select * from ad_campaigns where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Ad campaign"));
    }

    private static final String TOTALS = "coalesce(sum(m.spend),0) as spend, coalesce(sum(m.impressions),0) as impressions, coalesce(sum(m.clicks),0) as clicks, coalesce(sum(m.conversions),0) as conversions, coalesce(sum(m.revenue),0) as revenue";

    public List<Map<String, Object>> list(long ws) {
        List<Map<String, Object>> rows = db.list("select a.*, " + TOTALS + " from ad_campaigns a left join ad_metrics m on m.ad_campaign_id=a.id where a.workspace_id=:ws group by a.id order by a.id desc limit 300", "ws", ws);
        rows.forEach(AdsService::derive);
        return rows;
    }

    static void derive(Map<String, Object> m) {
        BigDecimal spend = Money.bd(m.get("spend"));
        double imp = ((Number) m.get("impressions")).doubleValue();
        double clk = ((Number) m.get("clicks")).doubleValue();
        double conv = ((Number) m.get("conversions")).doubleValue();
        BigDecimal rev = Money.bd(m.get("revenue"));
        m.put("ctr", imp == 0 ? 0 : round(clk * 100 / imp));
        m.put("cpc", clk == 0 ? null : spend.divide(BigDecimal.valueOf(clk), 2, RoundingMode.HALF_UP));
        m.put("cpa", conv == 0 ? null : spend.divide(BigDecimal.valueOf(conv), 2, RoundingMode.HALF_UP));
        m.put("roas", spend.signum() == 0 ? null : rev.divide(spend, 2, RoundingMode.HALF_UP));
        if (m.get("totalBudget") != null && Money.bd(m.get("totalBudget")).signum() > 0)
            m.put("budgetUsedPercent", spend.multiply(new BigDecimal("100")).divide(Money.bd(m.get("totalBudget")), 1, RoundingMode.HALF_UP));
    }

    static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private Map<String, Object> clean(Request r) {
        List<FieldErr> errs = new ArrayList<>();
        if (!List.of("GOOGLE", "META", "YOUTUBE", "LINKEDIN", "X", "OTHER").contains(r.platform())) errs.add(new FieldErr("platform", "ads.platform", "Choose the ad platform."));
        String name = Norm.plain(r.name());
        if (name == null || name.length() < 3 || name.length() > 100) errs.add(new FieldErr("name", "ads.name", "Name must be 3 to 100 characters."));
        String objective = r.objective() == null ? "LEADS" : r.objective();
        if (!List.of("AWARENESS", "TRAFFIC", "LEADS", "SALES", "APP_INSTALLS").contains(objective)) errs.add(new FieldErr("objective", "ads.objective", "Choose an objective."));
        if (r.dailyBudget() == null || r.dailyBudget().signum() < 0 || r.dailyBudget().compareTo(new BigDecimal("10000000")) > 0) errs.add(new FieldErr("dailyBudget", "ads.budget", "Enter the daily budget."));
        if (r.totalBudget() != null && r.totalBudget().signum() <= 0) errs.add(new FieldErr("totalBudget", "ads.budget", "Enter an amount above zero."));
        LocalDate s = date(r.startDate(), "startDate", errs);
        LocalDate e = date(r.endDate(), "endDate", errs);
        if (s != null && e != null && e.isBefore(s)) errs.add(new FieldErr("endDate", "ads.range", "The end date must be after the start date."));
        String status = r.status() == null ? "DRAFT" : r.status();
        if (!List.of("DRAFT", "ACTIVE", "PAUSED", "ENDED").contains(status)) errs.add(new FieldErr("status", "ads.status", "Choose a status."));
        String utm = Norm.text(r.utmCampaign());
        if (utm != null && !utm.matches("^[A-Za-z0-9_.-]{1,60}$")) errs.add(new FieldErr("utmCampaign", "ads.utm", "Use letters, digits, - _ . only."));
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("platform", r.platform());
        m.put("name", name);
        m.put("objective", objective);
        m.put("daily_budget", r.dailyBudget());
        m.put("total_budget", r.totalBudget());
        m.put("start_date", s);
        m.put("end_date", e);
        m.put("status", status);
        m.put("external_id", Norm.text(r.externalId()));
        m.put("utm_campaign", utm);
        m.put("notes", Norm.plain(r.notes()));
        return m;
    }

    private LocalDate date(String s, String field, List<FieldErr> errs) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            errs.add(new FieldErr(field, "ads.date", "Choose a valid date."));
            return null;
        }
    }

    public Map<String, Object> create(long ws, Request r) {
        plans.requireFeature(ws, "ads");
        Map<String, Object> m = clean(r);
        Object[] kv = new Object[m.size() * 2 + 2];
        kv[0] = "workspace_id";
        kv[1] = ws;
        int i = 2;
        for (var e : m.entrySet()) {
            kv[i++] = e.getKey();
            kv[i++] = e.getValue();
        }
        long id = db.insertInto("ad_campaigns", kv);
        audit.log("AD_CAMPAIGN_CREATED", "ad_campaigns", id);
        return get(ws, id);
    }

    public Map<String, Object> update(long ws, long id, Request r) {
        load(ws, id);
        db.updateTable("ad_campaigns", "id", id, clean(r), "workspace_id", ws);
        return get(ws, id);
    }

    public void delete(long ws, long id) {
        load(ws, id);
        db.update("delete from ad_campaigns where id=:id", "id", id);
    }

    public Map<String, Object> get(long ws, long id) {
        load(ws, id);
        Map<String, Object> m = db.one("select a.*, " + TOTALS + " from ad_campaigns a left join ad_metrics m on m.ad_campaign_id=a.id where a.id=:id group by a.id", "id", id).orElseThrow();
        derive(m);
        m.put("daily", db.list("select day, spend, impressions, clicks, conversions, revenue, source from ad_metrics where ad_campaign_id=:id order by day desc limit 90", "id", id));
        return m;
    }

    public Map<String, Object> addMetric(long ws, long id, Metric r) {
        load(ws, id);
        upsert(id, parse(r), "MANUAL");
        return get(ws, id);
    }

    private AdsClient.DayMetrics parse(Metric r) {
        List<FieldErr> errs = new ArrayList<>();
        LocalDate d = date(r.day(), "day", errs);
        if (d == null) errs.add(new FieldErr("day", "ads.date", "Choose a date."));
        else if (d.isAfter(Time.todayIst())) errs.add(new FieldErr("day", "ads.future", "The date cannot be in the future."));
        BigDecimal spend = r.spend() == null ? BigDecimal.ZERO : r.spend();
        BigDecimal rev = r.revenue() == null ? BigDecimal.ZERO : r.revenue();
        if (spend.signum() < 0 || rev.signum() < 0) errs.add(new FieldErr("spend", "ads.neg", "Amounts cannot be negative."));
        int imp = r.impressions() == null ? 0 : r.impressions();
        int clk = r.clicks() == null ? 0 : r.clicks();
        int cv = r.conversions() == null ? 0 : r.conversions();
        if (imp < 0 || clk < 0 || cv < 0) errs.add(new FieldErr("clicks", "ads.neg", "Counts cannot be negative."));
        if (imp > 0 && clk > imp) errs.add(new FieldErr("clicks", "ads.clicks", "Clicks cannot be more than impressions."));
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        return new AdsClient.DayMetrics(d, spend.doubleValue(), imp, clk, cv, rev.doubleValue());
    }

    private void upsert(long adId, AdsClient.DayMetrics m, String source) {
        db.update("insert into ad_metrics (ad_campaign_id, day, spend, impressions, clicks, conversions, revenue, source) values (:a,:d,:s,:i,:c,:v,:r,:src) " +
                        "on duplicate key update spend=:s, impressions=:i, clicks=:c, conversions=:v, revenue=:r, source=:src",
                "a", adId, "d", m.day(), "s", BigDecimal.valueOf(m.spend()).setScale(2, RoundingMode.HALF_UP), "i", m.impressions(), "c", m.clicks(), "v", m.conversions(),
                "r", BigDecimal.valueOf(m.revenue()).setScale(2, RoundingMode.HALF_UP), "src", source);
    }

    /** CSV with a header row: day, spend, impressions, clicks, conversions, revenue (extra columns ignored; column names are case-insensitive). */
    public Map<String, Object> importCsv(long ws, long id, byte[] data) {
        load(ws, id);
        if (data.length > 2_000_000) throw ApiException.badRequest("The file is too large.");
        int ok = 0;
        List<String> bad = new ArrayList<>();
        try (CSVParser p = CSVParser.parse(new StringReader(new String(data, java.nio.charset.StandardCharsets.UTF_8)), CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreHeaderCase(true).setTrim(true).build())) {
            if (!p.getHeaderMap().containsKey("day")) throw ApiException.invalid("file", "ads.csv", "The file needs a \"day\" column (YYYY-MM-DD).");
            for (CSVRecord r : p) {
                try {
                    Metric m = new Metric(r.get("day"), dec(r, "spend"), integer(r, "impressions"), integer(r, "clicks"), integer(r, "conversions"), dec(r, "revenue"));
                    upsert(id, parse(m), "IMPORT");
                    ok++;
                } catch (Exception e) {
                    if (bad.size() < 5) bad.add("Row " + r.getRecordNumber() + ": " + (e instanceof ApiException ? "invalid values" : "could not be read"));
                }
                if (ok + bad.size() > 5000) break;
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("The file could not be read as CSV.");
        }
        return Map.of("imported", ok, "problems", bad);
    }

    private static BigDecimal dec(CSVRecord r, String col) {
        if (!r.isMapped(col) || r.get(col).isBlank()) return null;
        return new BigDecimal(r.get(col).replace(",", "").replace("₹", ""));
    }

    private static Integer integer(CSVRecord r, String col) {
        if (!r.isMapped(col) || r.get(col).isBlank()) return null;
        return Integer.parseInt(r.get(col).replace(",", ""));
    }

    /** Pulls the last 14 days from the platform connector (sandbox generates sample numbers). */
    public Map<String, Object> sync(long ws, long id) {
        Row a = load(ws, id);
        LocalDate to = Time.todayIst().minusDays(1);
        LocalDate from = to.minusDays(13);
        if (a.date("start_date") != null && a.date("start_date").isAfter(from)) from = a.date("start_date");
        if (a.date("end_date") != null && a.date("end_date").isBefore(to)) to = a.date("end_date");
        if (from.isAfter(to)) throw ApiException.state("There are no completed days to sync yet.");
        List<AdsClient.DayMetrics> days;
        try {
            days = client.fetch(a.s("platform"), a.s("external_id") == null ? "ad" + id : a.s("external_id"), from, to, a.d("daily_budget").doubleValue());
        } catch (IllegalStateException e) {
            throw ApiException.state(e.getMessage());
        }
        for (AdsClient.DayMetrics d : days) upsert(id, d, "SYNC");
        return Map.of("synced", days.size());
    }

    /** Totals for the dashboard / analytics page. */
    public Map<String, Object> summary(long ws, LocalDate from, LocalDate to) {
        Map<String, Object> m = db.one("select " + TOTALS + " from ad_campaigns a join ad_metrics m on m.ad_campaign_id=a.id where a.workspace_id=:ws and m.day between :f and :t", "ws", ws, "f", from, "t", to).orElseThrow();
        derive(m);
        m.put("byPlatform", db.list("select a.platform, " + TOTALS + " from ad_campaigns a join ad_metrics m on m.ad_campaign_id=a.id where a.workspace_id=:ws and m.day between :f and :t group by a.platform", "ws", ws, "f", from, "t", to));
        ((List<Map<String, Object>>) m.get("byPlatform")).forEach(AdsService::derive);
        return m;
    }
}
