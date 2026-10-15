package com.marketingpanel.whatsapp;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.marketingpanel.common.Db;
import com.marketingpanel.common.Money;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Sell rate per message category and destination country (message_rates table, cached). */
@Service
public class RateService {
    private final Db db;

    public RateService(Db db) {
        this.db = db;
    }

    public static String countryCode(String phoneE164) {
        try {
            Phonenumber.PhoneNumber n = PhoneNumberUtil.getInstance().parse(phoneE164, null);
            return String.valueOf(n.getCountryCode());
        } catch (Exception e) {
            return "91";
        }
    }

    public BigDecimal sellRate(String category, String phoneE164) {
        return rateFor(category, countryCode(phoneE164)).sell();
    }

    public BigDecimal metaRate(String category, String phoneE164) {
        return rateFor(category, countryCode(phoneE164)).meta();
    }

    public record Rate(BigDecimal meta, BigDecimal sell) {}

    @Cacheable(cacheNames = "rates", key = "#category + '|' + #cc")
    public Rate rateFor(String category, String cc) {
        var r = db.row("select meta_rate, sell_rate from message_rates where category=:c and country_code=:cc and effective_from <= :d " +
                "order by effective_from desc limit 1", "c", category, "cc", cc, "d", LocalDate.now());
        if (r.isEmpty() && !"91".equals(cc))
            r = db.row("select meta_rate, sell_rate from message_rates where category=:c and country_code='91' and effective_from <= :d " +
                    "order by effective_from desc limit 1", "c", category, "d", LocalDate.now());
        if (r.isEmpty()) throw new IllegalStateException("No message rate configured for " + category);
        return new Rate(Money.rate(r.get().d("meta_rate")), Money.rate(r.get().d("sell_rate")));
    }

    @CacheEvict(cacheNames = "rates", allEntries = true)
    public void evict() {}
}
