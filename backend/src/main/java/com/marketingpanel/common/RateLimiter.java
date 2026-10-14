package com.marketingpanel.common;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Bucket4j based abuse limits (SRS 4.6). In-memory per node; a Redis backend can replace it at stage 3. */
@Component
public class RateLimiter {
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder().expireAfterAccess(2, TimeUnit.HOURS).maximumSize(200_000).build();

    /** Consume one token or throw RATE_LIMITED. */
    public void check(String key, int capacity, Duration period) {
        Bucket b = buckets.get(key + "|" + capacity + "|" + period.toSeconds(), k ->
                Bucket.builder().addLimit(Bandwidth.builder().capacity(capacity).refillIntervally(capacity, period).build()).build());
        ConsumptionProbe p = b.tryConsumeAndReturnRemaining(1);
        if (!p.isConsumed()) {
            throw ApiException.rateLimited((int) Math.max(1, TimeUnit.NANOSECONDS.toSeconds(p.getNanosToWaitForRefill())));
        }
    }

    public boolean tryConsume(String key, int capacity, Duration period) {
        try {
            check(key, capacity, period);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }
}
