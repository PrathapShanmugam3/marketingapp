package com.marketingpanel.campaign;

import com.marketingpanel.common.*;
import com.marketingpanel.whatsapp.WaGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** Scheduled-campaign start, A/B winner selection and the 48-hour wallet settlement. Called from ScheduledTasks. */
@Component
public class CampaignMaintenance {
    private static final Logger log = LoggerFactory.getLogger(CampaignMaintenance.class);
    private final Db db;
    private final CampaignService campaigns;
    private final WaGateway gw;
    private final WalletService wallet;
    private final NotificationService notifications;
    private final CampaignStats stats;

    public CampaignMaintenance(Db db, CampaignService campaigns, WaGateway gw, WalletService wallet, NotificationService notifications, CampaignStats stats) {
        this.db = db;
        this.campaigns = campaigns;
        this.gw = gw;
        this.wallet = wallet;
        this.notifications = notifications;
        this.stats = stats;
    }

    public int startDue() {
        int n = 0;
        for (Row c : db.rows("select id, workspace_id, name from campaigns where status='SCHEDULED' and scheduled_at <= :now order by scheduled_at limit 50", "now", Time.now())) {
            int claimed = db.update("update campaigns set status='QUEUED' where id=:id and status='SCHEDULED'", "id", c.l("id"));
            if (claimed == 0) continue;
            try {
                gw.sendable(c.l("workspace_id"));
                campaigns.queueRecipients(c.l("workspace_id"), c.l("id"));
                n++;
            } catch (ApiException e) {
                db.update("update campaigns set status='PAUSED', paused_by_system=1 where id=:id", "id", c.l("id"));
                notifications.notifyOwners(c.l("workspace_id"), "CAMPAIGN_PAUSED", "Campaign " + c.s("name") + " was paused", e.getMessage(), "/campaigns/" + c.l("id"));
            } catch (Exception e) {
                log.error("Could not start scheduled campaign {}", c.l("id"), e);
                db.update("update campaigns set status='SCHEDULED' where id=:id and status='QUEUED' and total_recipients=0", "id", c.l("id"));
            }
        }
        return n;
    }

    /** Picks the winner once the test window has passed: highest read (or click) rate. */
    public int pickWinners() {
        int n = 0;
        for (Row c : db.rows("select * from campaigns where ab_metric is not null and started_at is not null and " +
                "date_add(started_at, interval ab_winner_after_hours hour) <= :now and not exists (select 1 from campaign_variants v where v.campaign_id=campaigns.id and v.is_winner=1)",
                "now", Time.now())) {
            stats.refresh(c.l("id"));
            Row best = null;
            double bestRate = -1;
            for (Row v : db.rows("select * from campaign_variants where campaign_id=:c", "c", c.l("id"))) {
                double sent = Math.max(1, v.l("sent_count"));
                double rate = ("CLICK".equals(c.s("ab_metric")) ? v.l("click_count") : v.l("read_count")) / sent;
                if (rate > bestRate) {
                    bestRate = rate;
                    best = v;
                }
            }
            if (best != null) {
                db.update("update campaign_variants set is_winner=1 where id=:id", "id", best.l("id"));
                notifications.notifyOwners(c.l("workspace_id"), "AB_WINNER", "A/B winner picked for " + c.s("name"), "Variant " + best.s("label") + " won.", "/campaigns/" + c.l("id"));
                n++;
            }
        }
        return n;
    }

    /** Releases whatever is left of a campaign's reservation 48 h after it ended (messages that were sent but never delivered). */
    public int settle() {
        int n = 0;
        for (Row c : db.rows("select * from campaigns where status in ('COMPLETED','CANCELLED','FAILED') and completed_at is not null and completed_at < :t " +
                "and estimated_cost > 0 and not exists (select 1 from wallet_transactions w where w.workspace_id=campaigns.workspace_id and w.reference_type='CAMPAIGN' " +
                "and w.reference_id=campaigns.id and w.note like 'Final settlement%') limit 100", "t", Time.now().minusHours(48))) {
            long id = c.l("id");
            BigDecimal reserved = money("select coalesce(sum(amount),0) from wallet_transactions where reference_type='CAMPAIGN' and reference_id=:id and type='DEBIT' and note not like 'Test%'", id)
                    .subtract(money("select coalesce(sum(amount),0) from wallet_transactions where reference_type='CAMPAIGN' and reference_id=:id and type='REFUND' and note not like 'Test%'", id))
                    .subtract(money("select coalesce(sum(w.amount),0) from wallet_transactions w join campaign_messages m on m.id=w.reference_id where w.reference_type='CAMPAIGN_MESSAGE' and w.type='REFUND' and m.campaign_id=:id", id));
            BigDecimal actual = money("select coalesce(sum(cost),0) from campaign_messages where campaign_id=:id", id);
            BigDecimal back = reserved.subtract(actual);
            if (back.compareTo(new BigDecimal("0.0001")) > 0) {
                wallet.refund(c.l("workspace_id"), back, "CAMPAIGN", id, "Final settlement for campaign " + c.s("name"));
                n++;
            } else { // nothing to give back: write a zero-amount marker row so this campaign is not picked again
                db.insertInto("wallet_transactions", "workspace_id", c.l("workspace_id"), "type", "ADJUSTMENT", "amount", BigDecimal.ZERO.setScale(4),
                        "balance_after", wallet.balance(c.l("workspace_id")), "reference_type", "CAMPAIGN", "reference_id", id,
                        "note", "Final settlement for campaign " + c.s("name"));
            }
        }
        return n;
    }

    private BigDecimal money(String sql, long id) {
        return db.scalar(sql, BigDecimal.class, "id", id);
    }
}
