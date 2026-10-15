package com.marketingpanel.engage;

import com.marketingpanel.content.ContentService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.marketingpanel.config.Settings;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduled work of the marketing-suite modules (ShedLock keeps it to one node). */
@Component
public class SuiteScheduler {
    private static final Logger log = LoggerFactory.getLogger(SuiteScheduler.class);
    private final BroadcastService broadcasts;
    private final ContentService content;
    private final Settings settings;

    public SuiteScheduler(BroadcastService broadcasts, ContentService content, Settings settings) {
        this.settings = settings;
        this.broadcasts = broadcasts;
        this.content = content;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 25_000)
    @SchedulerLock(name = "suiteEveryThirtySeconds", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
    public void tick() {
        if (!settings.runsWorkers()) return;
        run("broadcasts.startDue", broadcasts::startDue);
        run("content.publishDue", content::publishDue);
    }

    private void run(String name, Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.error("Scheduled task {} failed", name, e);
        }
    }
}
