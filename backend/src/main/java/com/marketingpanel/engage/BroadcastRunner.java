package com.marketingpanel.engage;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.common.*;
import com.marketingpanel.jobs.JobHandler;
import com.marketingpanel.jobs.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Delivers a broadcast in time-boxed slices so one large send never blocks the job queue. */
@Component
public class BroadcastRunner implements JobHandler {
    private static final Logger log = LoggerFactory.getLogger(BroadcastRunner.class);
    private static final long SLICE_MS = 20_000;
    private final Db db;
    private final Notifier notifier;
    private final PushSender push;
    private final WalletService wallet;
    private final JobService jobs;
    private final BroadcastService svc;

    public BroadcastRunner(Db db, Notifier notifier, PushSender push, WalletService wallet, JobService jobs, BroadcastService svc) {
        this.db = db;
        this.notifier = notifier;
        this.push = push;
        this.wallet = wallet;
        this.jobs = jobs;
        this.svc = svc;
    }

    @Override
    public String type() {
        return "BROADCAST_RUN";
    }

    @Override
    public void handle(JsonNode p) {
        long id = p.get("broadcastId").asLong();
        Row b = db.row("select * from broadcasts where id=:id", "id", id).orElse(null);
        if (b == null || !"SENDING".equals(b.s("status"))) return;
        long ws = b.l("workspace_id");
        long deadline = System.currentTimeMillis() + SLICE_MS;
        if ("PUSH".equals(b.s("channel"))) {
            runPush(b, ws);
            return;
        }
        BigDecimal per = "SMS".equals(b.s("channel")) ? svc.smsCost(b.s("body")) : BigDecimal.ZERO;
        while (System.currentTimeMillis() < deadline) {
            List<Row> batch = db.rows("select r.id as rid, r.contact_id, c.full_name, c.email, c.phone, c.city, c.email_unsub_at, c.sms_unsub_at, c.opt_in_status from broadcast_recipients r " +
                    "join contacts c on c.id=r.contact_id where r.broadcast_id=:id and r.status='PENDING' order by r.id limit 50", "id", id);
            if (batch.isEmpty()) break;
            if (!"SENDING".equals(db.scalar("select status from broadcasts where id=:id", String.class, "id", id))) return; // cancelled meanwhile
            for (Row r : batch) {
                String status = "SENT";
                String error = null;
                try {
                    boolean unsubscribed = "EMAIL".equals(b.s("channel")) ? r.t("email_unsub_at") != null : r.t("sms_unsub_at") != null;
                    if (unsubscribed || !"OPTED_IN".equals(r.s("opt_in_status"))) {
                        status = "SKIPPED";
                        error = "Opted out";
                    } else if ("EMAIL".equals(b.s("channel"))) {
                        String link = svc.unsubscribeLink(ws, r.l("contact_id"), id, "EMAIL");
                        String html = BroadcastService.personalise(b.s("body"), r.s("full_name"), "", r.s("city"), true)
                                + "<hr><p style=\"font-size:12px;color:#667\">You received this because you are a customer. <a href=\"" + Html.esc(link) + "\">Unsubscribe</a></p>";
                        notifier.emailHtml(r.s("email"), b.s("subject"), html);
                    } else {
                        notifier.sms(r.s("phone"), BroadcastService.personalise(b.s("body"), r.s("full_name"), "", r.s("city"), false));
                    }
                } catch (Exception e) {
                    status = "FAILED";
                    error = String.valueOf(e.getMessage());
                    error = error.length() > 190 ? error.substring(0, 190) : error;
                    log.warn("Broadcast {} to contact {} failed: {}", id, r.l("contact_id"), error);
                }
                db.update("update broadcast_recipients set status=:s, error=:e, sent_at=:t where id=:id", "s", status, "e", error, "t", "SENT".equals(status) ? Time.now() : null, "id", r.l("rid"));
                if (!"SENT".equals(status)) {
                    if (per.signum() > 0) wallet.refund(ws, per, "BROADCAST", id, "Undelivered SMS refunded");
                }
                db.update("update broadcasts set sent_count=sent_count+:s, failed_count=failed_count+:f where id=:id", "s", "SENT".equals(status) ? 1 : 0, "f", "FAILED".equals(status) ? 1 : 0, "id", id);
            }
        }
        if (db.exists("select 1 from broadcast_recipients where broadcast_id=:id and status='PENDING' limit 1", "id", id)) {
            jobs.enqueue("BROADCAST_RUN", Map.of("broadcastId", id));
            return;
        }
        long sent = db.count("select count(*) from broadcast_recipients where broadcast_id=:id and status='SENT'", "id", id);
        db.update("update broadcasts set status=:s, completed_at=:now, cost=:cost where id=:id and status='SENDING'",
                "s", sent == 0 ? "FAILED" : "COMPLETED", "now", Time.now(), "cost", per.multiply(BigDecimal.valueOf(sent)), "id", id);
    }

    private void runPush(Row b, long ws) {
        long id = b.l("id");
        int sent = 0;
        int failed = 0;
        for (Row s : db.rows("select id, endpoint, p256dh, auth from push_subscriptions where workspace_id=:ws and active=1", "ws", ws)) {
            try {
                if (push.send(s.s("endpoint"), s.s("p256dh"), s.s("auth"), b.s("subject"), b.s("body"), null)) sent++;
                else db.update("update push_subscriptions set active=0 where id=:id", "id", s.l("id"));
            } catch (Exception e) {
                failed++;
            }
        }
        db.update("update broadcasts set status=:s, sent_count=:sent, failed_count=:f, completed_at=:now where id=:id", "s", sent == 0 ? "FAILED" : "COMPLETED", "sent", sent, "f", failed, "now", Time.now(), "id", id);
    }
}
