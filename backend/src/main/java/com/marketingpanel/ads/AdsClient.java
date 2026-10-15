package com.marketingpanel.ads;

import java.time.LocalDate;
import java.util.List;

/** Port to an ad platform's reporting API (Google Ads, Meta Marketing API ...). */
public interface AdsClient {
    record DayMetrics(LocalDate day, double spend, int impressions, int clicks, int conversions, double revenue) {}

    /** Daily metrics for [from, to]. Throws IllegalStateException when the platform is not connected. */
    List<DayMetrics> fetch(String platform, String externalId, LocalDate from, LocalDate to, double dailyBudget);
}
