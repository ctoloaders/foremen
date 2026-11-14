package com.foremen.config.nip;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * FOR-11-00 (Requirement 6.2) — binds the {@code foremen.nip-lookup} properties at startup.
 */
@Configuration
@EnableConfigurationProperties(NipLookupProperties.class)
public class NipLookupConfig {
}
