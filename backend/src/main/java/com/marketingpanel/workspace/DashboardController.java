package com.marketingpanel.workspace;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.AuthUser;
import com.marketingpanel.auth.Roles;
import com.marketingpanel.common.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** P06 Dashboard: counters come from campaign counters, never COUNT(*) over message tables. */
@RestController
public class DashboardController {
    private final Db db;
    private final WorkspaceService workspaces;

    public DashboardController(Db db, WorkspaceService workspaces) {
        this.db = db;
        this.workspaces = workspaces;
    }

    @GetMapping("/api/v1/dashboard")
    @PreAuthorize(Roles.MEMBER)
    public Map<String, Object> dashboard(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        AuthUser me = Auth.user();
        long ws = me.workspaceId() == null ? Auth.ws() : me.workspaceId();
        LocalDate today = LocalDate.now(Time.IST);
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end) || end.isAfter(today) || ChronoUnit.DAYS.between(start, end) > 365)
            throw ApiException.invalid("from", "dashboard.range", "Choose a range of up to 1 year.");
        var f = Time.startOfIstDayUtc(start);
        var t = Time.startOfIstDayUtc(end.plusDays(1));
        Map<String, Object> out = new LinkedHashMap<>();
        Row c = db.row("select coalesce(sum(sent_count),0) as sent, coalesce(sum(delivered_count),0) as delivered, coalesce(sum(read_count),0) as readc, coalesce(sum(failed_count),0) as failed, " +
                "coalesce(sum(replied_count),0) as replied, count(*) as campaigns from campaigns where workspace_id=:ws and started_at >= :f and started_at < :t", "ws", ws, "f", f, "t", t).orElseThrow();
        long sent = c.l("sent");
        out.put("messagesSent", sent);
        out.put("deliveryRate", sent == 0 ? 0 : Math.round(c.l("delivered") * 1000.0 / sent) / 10.0);
        out.put("readRate", sent == 0 ? 0 : Math.round(c.l("readc") * 1000.0 / sent) / 10.0);
        out.put("failed", c.l("failed"));
        out.put("replies", c.l("replied"));
        out.put("campaigns", c.l("campaigns"));
        out.put("sentByDay", db.list("select date(convert_tz(started_at,'+00:00','+05:30')) as day, sum(sent_count) as sent, sum(delivered_count) as delivered from campaigns " +
                "where workspace_id=:ws and started_at >= :f and started_at < :t group by day order by day", "ws", ws, "f", f, "t", t));
        // Staff see only their own leads
        String own = me.isStaffRole() ? " and assigned_to=:me" : "";
        Map<String, Object> leads = new LinkedHashMap<>();
        leads.put("new", db.count("select count(*) from leads where workspace_id=:ws and deleted_at is null and created_at >= :f and created_at < :t" + own, "ws", ws, "f", f, "t", t, "me", me.userId()));
        leads.put("open", db.count("select count(*) from leads where workspace_id=:ws and deleted_at is null and status in ('NEW','CONTACTED','INTERESTED')" + own, "ws", ws, "me", me.userId()));
        leads.put("overdueFollowUps", db.count("select count(*) from leads where workspace_id=:ws and deleted_at is null and status in ('NEW','CONTACTED','INTERESTED') and follow_up_at < :now" + own, "ws", ws, "now", Time.now(), "me", me.userId()));
        out.put("leads", leads);
        Map<String, Object> reviews = new LinkedHashMap<>();
        reviews.put("requests", db.count("select count(*) from review_requests where workspace_id=:ws and channel='WHATSAPP' and created_at >= :f and created_at < :t", "ws", ws, "f", f, "t", t));
        reviews.put("clicked", db.count("select count(*) from review_requests where workspace_id=:ws and channel='WHATSAPP' and clicked_at >= :f and clicked_at < :t", "ws", ws, "f", f, "t", t));
        reviews.put("latest", db.one("select rating, review_count, captured_on from review_stats where workspace_id=:ws order by captured_on desc limit 1", "ws", ws).orElse(null));
        out.put("reviews", reviews);
        BigDecimal balance = db.scalar("select wallet_balance from workspaces where id=:ws", BigDecimal.class, "ws", ws);
        BigDecimal lastCost = db.scalar("select estimated_cost from campaigns where workspace_id=:ws and status not in ('DRAFT') order by id desc limit 1", BigDecimal.class, "ws", ws);
        out.put("walletBalance", balance);
        out.put("lowWallet", balance.compareTo(new BigDecimal("100")) < 0 || (lastCost != null && balance.compareTo(lastCost) < 0));
        out.put("onboarding", workspaces.get(ws).get("onboarding"));
        out.put("range", Map.of("from", start.toString(), "to", end.toString()));
        return out;
    }
}
