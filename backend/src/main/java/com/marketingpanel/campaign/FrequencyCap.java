package com.marketingpanel.campaign;

import com.marketingpanel.common.Db;
import com.marketingpanel.common.Time;
import org.springframework.stereotype.Component;

/** Max N marketing messages per contact per 24 h across campaigns and automations (default 2, configurable 1-5). */
@Component
public class FrequencyCap {
    private final Db db;

    public FrequencyCap(Db db) {
        this.db = db;
    }

    public boolean exceeded(long ws, long contactId) {
        Integer cap = db.scalar("select marketing_freq_cap from workspaces where id=:ws", Integer.class, "ws", ws);
        int max = cap == null ? 2 : cap;
        var t = Time.now().minusHours(24);
        long n = db.count("select (select count(*) from campaign_messages cm join campaigns ca on ca.id=cm.campaign_id join wa_templates t on t.id=ca.template_id " +
                        "where cm.workspace_id=:ws and cm.contact_id=:c and cm.sent_at > :t and cm.status in ('SENT','DELIVERED','READ') and t.category='MARKETING') + " +
                        "(select count(*) from messages m join conversations cv on cv.id=m.conversation_id where cv.workspace_id=:ws and cv.contact_id=:c " +
                        "and m.direction='OUT' and m.category='MARKETING' and m.status<>'FAILED' and m.created_at > :t)",
                "ws", ws, "c", contactId, "t", t);
        return n >= max;
    }
}
