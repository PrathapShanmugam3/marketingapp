package com.marketingpanel.jobs;

import com.marketingpanel.affiliate.ConversionService;
import com.marketingpanel.affiliate.OfferService;
import com.marketingpanel.billing.BillingService;
import com.marketingpanel.campaign.CampaignMaintenance;
import com.marketingpanel.common.Db;
import com.marketingpanel.common.Row;
import com.marketingpanel.common.Time;
import com.marketingpanel.config.Settings;
import com.marketingpanel.crm.LeadService;
import com.marketingpanel.growth.AutomationEngine;
import com.marketingpanel.growth.DomainService;
import com.marketingpanel.payout.FinanceService;
import com.marketingpanel.payout.LedgerGuard;
import com.marketingpanel.payout.PayoutService;
import com.marketingpanel.social.SocialService;
import com.marketingpanel.support.SupportService;
import com.marketingpanel.whatsapp.TemplateService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * All @Scheduled work (SRS 3.3). Each task holds a ShedLock so it runs once across app nodes, and a failure in one
 * task never stops the others. Dedicated worker nodes run the same jar with app.role=worker.
 */
@Component
public class ScheduledTasks {
    private static final Logger log = LoggerFactory.getLogger(ScheduledTasks.class);
    private final Settings settings;
    private final Db db;
    private final CampaignMaintenance campaigns;
    private final AutomationEngine automations;
    private final SocialService social;
    private final LeadService leads;
    private final ConversionService conversions;
    private final OfferService offers;
    private final PayoutService payouts;
    private final TemplateService templates;
    private final DomainService domains;
    private final BillingService billing;
    private final LedgerGuard ledger;
    private final FinanceService finance;
    private final SupportService support;

    public ScheduledTasks(Settings settings, Db db, CampaignMaintenance campaigns, AutomationEngine automations, SocialService social, LeadService leads, ConversionService conversions,
                          OfferService offers, PayoutService payouts, TemplateService templates, DomainService domains, BillingService billing, LedgerGuard ledger,
                          FinanceService finance, SupportService support) {
        this.settings = settings;
        this.db = db;
        this.campaigns = campaigns;
        this.automations = automations;
        this.social = social;
        this.leads = leads;
        this.conversions = conversions;
        this.offers = offers;
        this.payouts = payouts;
        this.templates = templates;
        this.domains = domains;
        this.billing = billing;
        this.ledger = ledger;
        this.finance = finance;
        this.support = support;
    }

    private void safe(String name, Runnable r) {
        if (!settings.runsWorkers()) return;
        try {
            r.run();
        } catch (Exception e) {
            log.error("Scheduled task {} failed", name, e);
        }
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 20_000)
    @SchedulerLock(name = "everyThirtySeconds", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
    public void everyThirtySeconds() {
        safe("campaigns.startDue", campaigns::startDue);
        safe("automations.processDue", automations::processDue);
        safe("social.enqueueDue", social::enqueueDue);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    @SchedulerLock(name = "everyMinute", lockAtMostFor = "PT5M", lockAtLeastFor = "PT20S")
    public void everyMinute() {
        safe("leads.reminders", leads::sendReminders);
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    @SchedulerLock(name = "everyFiveMinutes", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void everyFiveMinutes() {
        safe("conversions.releaseHolds", conversions::releaseHolds);
        safe("offers.autoPause", offers::autoPause);
        safe("payouts.poll", payouts::pollProcessing);
        safe("automations.birthdays", automations::startBirthdays);
        safe("templates.sync", () -> {
            for (Row r : db.rows("select distinct workspace_id from wa_templates where status='PENDING' limit 200")) templates.sync(r.l("workspace_id"));
        });
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    @SchedulerLock(name = "hourly", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    public void hourly() {
        safe("campaigns.winners", campaigns::pickWinners);
        safe("campaigns.settle", campaigns::settle);
        safe("domains.recheck", domains::recheck); // every 6 h per domain, enforced inside
        safe("trials.expire", () -> db.update("update workspaces set status='PAST_DUE' where status='TRIAL' and trial_ends_at < :now", "now", Time.now()));
    }

    /** 02:30 IST: ledger check, reconciliation of the previous day, renewals, ticket auto-close, segment counts. */
    @Scheduled(cron = "0 30 2 * * *", zone = "Asia/Kolkata")
    @SchedulerLock(name = "nightly", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    public void nightly() {
        safe("ledger.check", ledger::check);
        safe("finance.reconcile", () -> finance.reconcile(LocalDate.now(Time.IST).minusDays(1), null));
        safe("billing.renewals", billing::renewals);
        safe("support.autoClose", support::autoClose);
        safe("segments.counts", () -> {
            for (Row s : db.rows("select id, workspace_id, filter_json from segments")) {
                try {
                    var q = com.marketingpanel.crm.AudienceQuery.fromFilter(com.marketingpanel.common.Json.tree(s.s("filter_json")));
                    java.util.Map<String, Object> p = new java.util.LinkedHashMap<>(q.params);
                    p.put("ws", s.l("workspace_id"));
                    Object[] kv = new Object[p.size() * 2];
                    int i = 0;
                    for (var e : p.entrySet()) {
                        kv[i++] = e.getKey();
                        kv[i++] = e.getValue();
                    }
                    long n = db.count("select count(*) from contacts c where " + com.marketingpanel.crm.AudienceQuery.BASE + " and (" + q.sql + ")", kv);
                    db.update("update segments set contact_count=:n where id=:id", "n", n, "id", s.l("id"));
                } catch (Exception ignored) {
                    // a broken segment must not stop the others
                }
            }
        });
        safe("jobs.cleanup", () -> db.update("delete from jobs where status='DONE' and updated_at < :t", "t", Time.now().minusDays(7)));
    }
}
