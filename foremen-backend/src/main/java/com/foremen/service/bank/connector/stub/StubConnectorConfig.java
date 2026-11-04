package com.foremen.service.bank.connector.stub;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * FOR-07-04 (Requirement 4.4): registers the {@code STUB} connector beans only when
 * {@code foremen.bank-feed.stub.enabled = true} (default false; never in production).
 */
@Configuration
@ConditionalOnProperty(prefix = "foremen.bank-feed.stub", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(StubProperties.class)
public class StubConnectorConfig {

    @Bean
    public StubDataStore stubDataStore() {
        return new StubDataStore();
    }

    @Bean
    public StubConnector stubConnector(StubDataStore stubDataStore, StubProperties stubProperties) {
        return new StubConnector(stubDataStore, stubProperties, Clock.systemUTC());
    }
}
