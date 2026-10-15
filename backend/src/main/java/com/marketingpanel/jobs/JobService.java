package com.marketingpanel.jobs;

import com.marketingpanel.common.Db;
import com.marketingpanel.common.Json;
import com.marketingpanel.common.Time;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** MySQL-backed job queue (SRS 3.3). Enqueue inside the business transaction for outbox semantics. */
@Service
public class JobService {
    private final Db db;

    public JobService(Db db) {
        this.db = db;
    }

    public long enqueue(String type, Object payload) {
        return enqueue(type, payload, Time.now(), 5, 5);
    }

    public long enqueue(String type, Object payload, LocalDateTime runAt) {
        return enqueue(type, payload, runAt, 5, 5);
    }

    public long enqueue(String type, Object payload, LocalDateTime runAt, int priority, int maxAttempts) {
        return db.insertInto("jobs", "type", type, "payload", Json.write(payload), "run_at", runAt, "priority", priority,
                "max_attempts", maxAttempts);
    }
}
