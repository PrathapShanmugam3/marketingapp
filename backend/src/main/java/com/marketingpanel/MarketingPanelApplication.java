package com.marketingpanel;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableCaching
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class MarketingPanelApplication {
    public static void main(String[] args) {
        SpringApplication.run(MarketingPanelApplication.class, args);
    }
}
