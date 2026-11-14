package com.foremen.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** FOR-11-00: the application {@link Clock} used for the procurement overdue rule (test seam). */
@Configuration
public class ProcurementClockConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock procurementClock() {
        return Clock.systemUTC();
    }
}
