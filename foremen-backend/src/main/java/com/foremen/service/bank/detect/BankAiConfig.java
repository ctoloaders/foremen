package com.foremen.service.bank.detect;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * FOR-07-04 (Requirement 15.1) — binds {@link BankAiProperties} ({@code foremen.bank-ledger.ai})
 * unconditionally so {@code DetectionService} can read {@code max-per-run} regardless of which
 * classifier implementation is wired.
 */
@Configuration
@EnableConfigurationProperties(BankAiProperties.class)
public class BankAiConfig {
}
