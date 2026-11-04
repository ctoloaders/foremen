package com.foremen.service.bank.connector.saltedge;

import com.foremen.service.bank.PublicBaseUrl;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * FOR-07-04 (Requirement 8; design §SaltEdgeClient): binds {@link SaltEdgeProperties} and exposes the
 * shared {@link SaltEdgeClient}. The client bean is always present (cheap, makes no calls at
 * startup); availability is decided by {@link SaltEdgeProperties#isAvailable(PublicBaseUrl)}. When
 * Salt Edge is enabled but misconfigured, the problems (property names only, never values) are logged
 * once at startup as a configuration error (R8.2).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SaltEdgeProperties.class)
public class SaltEdgeClientConfig {

    private static final Logger log = LoggerFactory.getLogger(SaltEdgeClientConfig.class);

    @Bean
    public SaltEdgeClient saltEdgeClient(SaltEdgeProperties properties, PublicBaseUrl publicBaseUrl) {
        if (properties.enabled()) {
            List<String> problems = properties.configurationProblems(publicBaseUrl);
            if (problems.isEmpty()) {
                log.info("Salt Edge connector configured: mode={}, baseUrl={}, signing={}",
                        properties.mode(), properties.effectiveBaseUrl(), properties.signingEnabled());
            } else {
                log.error("Salt Edge connector is enabled but unavailable — configuration error: {}", problems);
            }
        }
        return SaltEdgeClient.create(properties, Clock.systemUTC());
    }

    /**
     * The callback signature verifier (R10.2): the configured
     * {@code foremen.bank-feed.saltedge.callback-public-key} overrides the bundled documented keys. An
     * unparsable configured key is logged (never its value) and the bundled keys are used, so startup
     * does not fail on a bad key (R8.3).
     */
    @Bean
    public SaltEdgeCallbackVerifier saltEdgeCallbackVerifier(SaltEdgeProperties properties) {
        String configured = properties.callbackPublicKey();
        if (configured == null) {
            return SaltEdgeCallbackVerifier.withBundledKeys();
        }
        try {
            return SaltEdgeCallbackVerifier.withConfiguredKey(configured);
        } catch (RuntimeException e) {
            log.error("foremen.bank-feed.saltedge.callback-public-key is not a valid PEM public key; "
                    + "using the bundled Salt Edge callback keys");
            return SaltEdgeCallbackVerifier.withBundledKeys();
        }
    }

    /**
     * The {@code SALT_EDGE} connector (R9.9): always registered; the mode port is chosen by
     * {@code foremen.bank-feed.saltedge.mode}. With incomplete configuration it reports
     * {@code isAvailable() = false} instead of failing startup (R8.3).
     */
    @Bean
    public SaltEdgeConnector saltEdgeConnector(SaltEdgeClient saltEdgeClient, SaltEdgeProperties properties,
                                               PublicBaseUrl publicBaseUrl,
                                               @Value("${foremen.bank-feed.consent.warn-days:"
                                                       + SaltEdgeConnector.DEFAULT_WARN_DAYS + "}") int warnDays) {
        return new SaltEdgeConnector(SaltEdgeModeApi.forMode(saltEdgeClient), properties, publicBaseUrl,
                Clock.systemUTC(), warnDays);
    }
}
