package com.marketingpanel.ads;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Sandbox: deterministic, plausible numbers per campaign and day so dashboards can be tried without an ad account. */
@Component
@ConditionalOnProperty(name = "app.ads.mock", havingValue = "true", matchIfMissing = true)
public class MockAdsClient implements AdsClient {
    @Override
    public List<DayMetrics> fetch(String platform, String externalId, LocalDate from, LocalDate to, double dailyBudget) {
        List<DayMetrics> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Random r = new Random((externalId + d).hashCode());
            double spend = Math.round(dailyBudget * (0.7 + r.nextDouble() * 0.3) * 100) / 100.0;
            int impressions = (int) (spend * (40 + r.nextInt(40)));
            int clicks = (int) (impressions * (0.01 + r.nextDouble() * 0.03));
            int conv = (int) Math.round(clicks * (0.02 + r.nextDouble() * 0.06));
            double revenue = Math.round(conv * (400 + r.nextInt(900)) * 100) / 100.0;
            out.add(new DayMetrics(d, spend, impressions, clicks, conv, revenue));
        }
        return out;
    }
}
