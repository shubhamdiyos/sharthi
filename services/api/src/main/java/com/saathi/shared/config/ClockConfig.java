package com.saathi.shared.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock is a bean, never a static call. Tests assert expiry and business-day behaviour
 * by supplying a fixed clock, which is the only reason "no timers in the order lifecycle"
 * (§6.5) is a tolerable rule rather than an unverifiable one.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /** Siwan operates in one zone; business_day and quote expiry are evaluated in it (§4.6). */
    public static final ZoneId OPERATIONS_ZONE = ZoneId.of("Asia/Kolkata");

    @Bean
    public Clock clock() {
        return Clock.system(OPERATIONS_ZONE);
    }
}
