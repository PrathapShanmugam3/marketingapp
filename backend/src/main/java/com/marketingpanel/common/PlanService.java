package com.marketingpanel.common;

import org.springframework.stereotype.Service;

import java.util.Map;

/** Plan limits and feature flags per workspace (plans table). */
@Service
public class PlanService {
    private final Db db;

    public PlanService(Db db) {
        this.db = db;
    }

    public Row plan(long ws) {
        return db.row("select p.* from plans p join workspaces w on w.plan_id=p.id where w.id=:ws", "ws", ws)
                .orElseThrow(() -> ApiException.notFound("Plan"));
    }

    @SuppressWarnings("unchecked")
    public boolean hasFeature(long ws, String feature) {
        Object f = plan(ws).api().get("features");
        return f instanceof Map<?, ?> m && Boolean.TRUE.equals(((Map<String, Object>) m).get(feature));
    }

    public void requireFeature(long ws, String feature) {
        if (!hasFeature(ws, feature))
            throw ApiException.planLimit("Your plan does not include this feature. Please upgrade.");
    }

    public void checkContactLimit(long ws, int adding) {
        long max = plan(ws).l("max_contacts");
        long now = db.count("select count(*) from contacts where workspace_id=:ws and deleted_at is null", "ws", ws);
        if (now + adding > max) throw ApiException.planLimit("Your plan allows " + max + " contacts. Upgrade to add more.");
    }
}
