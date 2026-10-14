package com.marketingpanel.campaign;

import com.marketingpanel.common.Db;
import com.marketingpanel.common.Row;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Counters come from campaign_messages (never COUNT(*) on dashboards) and are pushed over STOMP. */
@Component
public class CampaignStats {
    private final Db db;
    private final SimpMessagingTemplate ws;

    public CampaignStats(Db db, SimpMessagingTemplate ws) {
        this.db = db;
        this.ws = ws;
    }

    public Map<String, Object> refresh(long campaignId) {
        Map<String, Long> by = new HashMap<>();
        for (Row r : db.rows("select status, count(*) as n from campaign_messages where campaign_id=:c group by status", "c", campaignId))
            by.put(r.s("status"), r.l("n"));
        long queued = by.getOrDefault("QUEUED", 0L), sent = by.getOrDefault("SENT", 0L), delivered = by.getOrDefault("DELIVERED", 0L),
                read = by.getOrDefault("READ", 0L), failed = by.getOrDefault("FAILED", 0L);
        BigDecimal cost = db.scalar("select coalesce(sum(cost),0) from campaign_messages where campaign_id=:c", BigDecimal.class, "c", campaignId);
        long replied = db.count("select count(*) from campaign_messages cm join conversations cv on cv.workspace_id=cm.workspace_id and cv.contact_id=cm.contact_id " +
                "where cm.campaign_id=:c and cm.sent_at is not null and cv.last_inbound_at > cm.sent_at and cv.last_inbound_at < date_add(cm.sent_at, interval 72 hour)", "c", campaignId);
        db.update("update campaigns set sent_count=:s, delivered_count=:d, read_count=:r, failed_count=:f, replied_count=:p, actual_cost=:a where id=:id",
                "s", sent + delivered + read, "d", delivered + read, "r", read, "f", failed, "p", replied, "a", cost, "id", campaignId);
        for (Row v : db.rows("select id from campaign_variants where campaign_id=:c", "c", campaignId)) {
            long vs = 0, vr = 0;
            for (Row x : db.rows("select status, count(*) as n from campaign_messages where variant_id=:v group by status", "v", v.l("id"))) {
                String st = x.s("status");
                if (!"QUEUED".equals(st) && !"FAILED".equals(st)) vs += x.l("n");
                if ("READ".equals(st)) vr += x.l("n");
            }
            long clicks = db.count("select count(*) from link_clicks lc join campaign_messages cm on cm.contact_id=lc.contact_id and cm.campaign_id=:c " +
                    "where lc.is_bot=0 and cm.variant_id=:v and lc.short_link_id in (select id from short_links where campaign_id=:c)", "c", campaignId, "v", v.l("id"));
            db.update("update campaign_variants set sent_count=:s, read_count=:r, click_count=:k where id=:id", "s", vs, "r", vr, "k", clicks, "id", v.l("id"));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("queued", queued);
        m.put("sent", sent + delivered + read);
        m.put("delivered", delivered + read);
        m.put("read", read);
        m.put("failed", failed);
        m.put("replied", replied);
        m.put("actualCost", cost);
        return m;
    }

    public void push(long workspaceId, long campaignId, String status, Map<String, Object> counters) {
        Map<String, Object> msg = new LinkedHashMap<>(counters);
        msg.put("campaignId", campaignId);
        msg.put("status", status);
        ws.convertAndSend("/topic/ws/" + workspaceId + "/campaign/" + campaignId, msg);
    }
}
