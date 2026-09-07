package com.foremen.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Startup smoke tests for {@link GooglePlacesProperties} binding and its fail-fast invariant.
 *
 * <p>Uses {@link ApplicationContextRunner} to bind the {@code foremen.google-places.*}
 * properties without booting the full application (no Testcontainers), and verify:
 * <ul>
 *   <li>The context FAILS to start when the feature is enabled but the API key is blank
 *       (Requirements 5.5, 7.10). The failure's root cause carries the actionable fail-fast
 *       message thrown by the record's canonical constructor.</li>
 *   <li>The context starts cleanly when the feature is enabled with a non-blank key.</li>
 *   <li>The context starts cleanly when the feature is disabled even with a blank key
 *       (a blank key is legitimate when the Places feature is off).</li>
 * </ul>
 */
@DisplayName("GooglePlacesConfigStartup")
class GooglePlacesConfigStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Configuration
    @EnableConfigurationProperties(GooglePlacesProperties.class)
    static class TestConfig {
    }

    // --- Requirements 5.5, 7.10: fail-fast when enabled with a blank key ---

    @Test
    @DisplayName("fails startup when enabled=true and api-key is blank")
    void failsWhenEnabledAndApiKeyBlank() {
        runner.withPropertyValues(
                        "foremen.google-places.enabled=true",
                        "foremen.google-places.api-key=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .as("startup must abort with the fail-fast configuration error")
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("foremen.google-places.enabled is true")
                            .hasMessageContaining("foremen.google-places.api-key");
                });
    }

    @Test
    @DisplayName("fails startup when enabled=true and api-key is only whitespace")
    void failsWhenEnabledAndApiKeyWhitespace() {
        runner.withPropertyValues(
                        "foremen.google-places.enabled=true",
                        "foremen.google-places.api-key=   ")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class);
                });
    }

    // --- Positive cases: the context starts cleanly ---

    @Test
    @DisplayName("starts cleanly when enabled=true with a non-blank api-key")
    void startsWhenEnabledWithNonBlankKey() {
        runner.withPropertyValues(
                        "foremen.google-places.enabled=true",
                        "foremen.google-places.api-key=test-places-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GooglePlacesProperties props = context.getBean(GooglePlacesProperties.class);
                    assertThat(props.enabled()).isTrue();
                    assertThat(props.apiKey()).isEqualTo("test-places-key");
                });
    }

    @Test
    @DisplayName("starts cleanly when enabled=false even with a blank api-key")
    void startsWhenDisabledWithBlankKey() {
        runner.withPropertyValues(
                        "foremen.google-places.enabled=false",
                        "foremen.google-places.api-key=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GooglePlacesProperties props = context.getBean(GooglePlacesProperties.class);
                    assertThat(props.enabled()).isFalse();
                });
    }
}
