package com.marketingpanel.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.common.Db;
import com.marketingpanel.common.Json;
import com.marketingpanel.common.Row;
import com.marketingpanel.common.Time;
import com.marketingpanel.config.Settings;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Polls the jobs table every second and claims due jobs with SELECT ... FOR UPDATE SKIP LOCKED, so several
 * app nodes can poll safely. Handlers run on Java 21 virtual threads.
 */
@Component
public class JobWorker {
    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private static final Duration[] BACKOFF = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofHours(1), Duration.ofHours(6)};

    private final Db db;
    private final Settings settings;
    private final Map<String, JobHandler> handlers = new HashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final String nodeId = "node-" + UUID.randomUUID().toString().substring(0, 8);
    private final MeterRegistry metrics;

    public JobWorker(Db db, Settings settings, List<JobHandler> hs, MeterRegistry metrics) {
        this.db = db;
        this.settings = settings;
        this.metrics = metrics;
        for (JobHandler h : hs) handlers.put(h.type(), h);
    }

    @PreDestroy
    void stop() {
        executor.shutdown();
    }

    @Scheduled(fixedDelayString = "${app.jobs.poll-ms:1000}")
    public void poll() {
        if (!settings.runsWorkers()) return;
        try {
            for (Row job : claim(20)) executor.submit(() -> run(job));
        } catch (Exception e) {
            log.warn("Job poll failed: {}", e.toString());
        }
    }

    /** Releases jobs whose worker died (locked for more than 10 minutes). */
    @Scheduled(fixedDelay = 60_000)
    public void reap() {
        if (!settings.runsWorkers()) return;
        db.update("update jobs set status='PENDING', locked_by=null, locked_at=null where status='RUNNING' and locked_at < :t",
                "t", Time.now().minusMinutes(10));
    }

    /** Synchronously runs every job that is due; used by tests and by the admin "run now" hook. */
    public int runDueNow() {
        int n = 0;
        List<Row> jobs;
        while (!(jobs = claim(50)).isEmpty()) {
            for (Row j : jobs) run(j);
            n += jobs.size();
        }
        return n;
    }

    List<Row> claim(int limit) {
        return db.tx(() -> {
            List<Row> due = db.rows("select * from jobs where status='PENDING' and run_at <= :now order by priority, run_at limit " + limit +
                    " for update skip locked", "now", Time.now());
            for (Row r : due) {
                db.update("update jobs set status='RUNNING', locked_by=:n, locked_at=:now, attempts=attempts+1 where id=:id",
                        "n", nodeId, "now", Time.now(), "id", r.l("id"));
            }
            return due;
        });
    }

    void run(Row job) {
        long id = job.l("id");
        String type = job.s("type");
        int attempts = job.i("attempts") + 1;
        JobHandler h = handlers.get(type);
        JsonNode payload = Json.tree(job.s("payload"));
        try {
            if (h == null) throw new IllegalStateException("No handler for job type " + type);
            h.handle(payload);
            db.update("update jobs set status='DONE', locked_by=null, locked_at=null, last_error=null where id=:id", "id", id);
            metrics.counter("jobs.done", "type", type).increment();
        } catch (Throwable t) {
            String err = t.toString();
            if (err.length() > 1000) err = err.substring(0, 1000);
            log.warn("Job {} ({}) attempt {} failed: {}", id, type, attempts, err);
            metrics.counter("jobs.failed", "type", type).increment();
            if (attempts >= job.i("max_attempts")) {
                db.update("update jobs set status='DEAD', locked_by=null, locked_at=null, last_error=:e where id=:id", "e", err, "id", id);
                log.error("Job {} ({}) is DEAD after {} attempts", id, type, attempts);
                try {
                    if (h != null) h.onDead(payload, err);
                } catch (Exception ex) {
                    log.error("onDead failed for job {}", id, ex);
                }
            } else {
                Duration d = BACKOFF[Math.min(attempts - 1, BACKOFF.length - 1)];
                db.update("update jobs set status='PENDING', locked_by=null, locked_at=null, run_at=:t, last_error=:e where id=:id",
                        "t", Time.now().plus(d), "e", err, "id", id);
            }
        }
    }
}
