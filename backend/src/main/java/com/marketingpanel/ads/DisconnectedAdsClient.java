package com.marketingpanel.ads;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Used when ADS_MOCK=false and no platform connector is installed: syncing explains that numbers must be entered or imported (CSV),
 * which always works. Provide an AdsClient bean (Google Ads API / Meta Marketing API) to enable automatic sync.
 */
@Component
@ConditionalOnProperty(name = "app.ads.mock", havingValue = "false")
public class DisconnectedAdsClient implements AdsClient {
    @Override
    public List<DayMetrics> fetch(String platform, String externalId, LocalDate from, LocalDate to, double dailyBudget) {
        throw new IllegalStateException("Automatic sync is not connected for " + platform + ". Enter the numbers or import a CSV report from the ad platform.");
    }
}
