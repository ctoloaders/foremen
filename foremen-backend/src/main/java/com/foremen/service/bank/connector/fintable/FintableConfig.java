package com.foremen.service.bank.connector.fintable;

import com.foremen.dao.BankFeedProviderStateDao;
import com.foremen.service.bank.PublicBaseUrl;
import com.foremen.service.bank.connection.BoundConnectionIds;
import java.time.Clock;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * FOR-07-04a (Requirements 1.1, 2.1, 2.2): active only when
 * {@code foremen.bank-feed.fintable.enabled=true}; with the flag off no Fintable bean exists and the
 * registry has no {@code FINTABLE} connector. Binds {@link FintableProperties} and logs, once, the
 * names of the failing properties (never values) when enabled but unavailable. Tasks 2.2 / 2.4 add the
 * client and connector beans to this class.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "foremen.bank-feed.fintable", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(FintableProperties.class)
public class FintableConfig {

    private static final Logger log = LoggerFactory.getLogger(FintableConfig.class);

    /** Evaluates availability once at start-up and logs the result; never fails start-up. */
    @Bean
    public FintableAvailabilityReport fintableAvailabilityReport(FintableProperties properties,
                                                                 PublicBaseUrl publicBaseUrl) {
        List<String> problems = properties.availabilityProblems(publicBaseUrl);
        if (problems.isEmpty()) {
            log.info("Fintable connector configured: {}", properties);
        } else {
            log.error("Fintable connector is enabled but unavailable — configuration error: {}", problems);
        }
        return new FintableAvailabilityReport(problems);
    }

    /** The Fintable HTTP client (Requirement 3); the token comes from {@code api-token} (resolved secret). */
    @Bean
    public FintableClient fintableClient(FintableProperties properties) {
        return FintableClient.create(properties, properties::apiToken);
    }

    /** In-memory refresh / link marks used by the connector (Requirement 9.7). */
    @Bean
    public FintableRefreshTracker fintableRefreshTracker() {
        return new FintableRefreshTracker();
    }

    /**
     * The Fintable connector (Requirements 4-10). {@link BoundConnectionIds} is injected lazily because its
     * implementation, {@code BankConnectionService}, depends on the connector registry.
     */
    @Bean
    public FintableConnector fintableConnector(FintableClient client, FintableProperties properties,
                                               PublicBaseUrl publicBaseUrl,
                                               @Lazy BoundConnectionIds boundConnectionIds,
                                               FintableRefreshTracker tracker) {
        return new FintableConnector(client, properties, publicBaseUrl, boundConnectionIds, tracker,
                Clock.systemUTC());
    }

    /** Daily token-expiry check (Requirement 12); logs one WARN at start-up when the date is blank. */
    @Bean
    public FintableTokenExpiryCheck fintableTokenExpiryCheck(FintableProperties properties,
                                                             BankFeedProviderStateDao stateDao,
                                                             ApplicationEventPublisher publisher,
                                                             PlatformTransactionManager transactionManager) {
        FintableTokenExpiryCheck check =
                new FintableTokenExpiryCheck(properties, stateDao, publisher, transactionManager);
        check.warnIfExpiryBlank();
        return check;
    }

    /** Start-up availability result (property names only). */
    public record FintableAvailabilityReport(List<String> problems) {
        public boolean available() {
            return problems.isEmpty();
        }
    }
}
