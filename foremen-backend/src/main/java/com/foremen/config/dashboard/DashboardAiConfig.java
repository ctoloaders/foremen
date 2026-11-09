package com.foremen.config.dashboard;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * FOR-10 — enables the {@code foremen.dashboard.ai} properties.
 */
@Configuration
@EnableConfigurationProperties(DashboardAiProperties.class)
public class DashboardAiConfig {
}
