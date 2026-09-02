package com.foremen.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for {@link ProjectAccessProperties} binding: the code-level default and
 * fail-fast validation.
 *
 * <p>Uses {@link ApplicationContextRunner} to bind the {@code foremen.project-access.*}
 * properties and verify:
 * <ul>
 *   <li>{@code foremen.project-access.cache-ttl-minutes} defaults to 1440 (24 hours) when
 *       unset, so profiles without explicit config start cleanly (Requirement 5.2).</li>
 *   <li>Startup fails for a non-positive {@code cache-ttl-minutes} (Requirement 5.3).</li>
 * </ul>
 */
@DisplayName("ProjectAccessProperties")
class ProjectAccessPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Configuration
    @EnableConfigurationProperties(ProjectAccessProperties.class)
    static class TestConfig {
    }

    // --- Requirement 5.2: code-level default of 1440 when the property is unset ---

    @Test
    @DisplayName("defaults cache-ttl-minutes to 1440 (24 hours) when unset")
    void defaultsCacheTtlToTwentyFourHoursWhenUnset() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ProjectAccessProperties props = context.getBean(ProjectAccessProperties.class);
            assertThat(props.cacheTtlMinutes())
                    .as("idle TTL must default to 1440 minutes (24 hours) when unset")
                    .isEqualTo(1440);
        });
    }

    @Test
    @DisplayName("binds an explicit positive cache-ttl-minutes")
    void bindsExplicitPositiveCacheTtl() {
        runner.withPropertyValues("foremen.project-access.cache-ttl-minutes=30")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ProjectAccessProperties props = context.getBean(ProjectAccessProperties.class);
                    assertThat(props.cacheTtlMinutes()).isEqualTo(30);
                });
    }

    // --- Requirement 5.3: fail-fast on a non-positive cache-ttl-minutes ---

    @Test
    @DisplayName("fails startup when cache-ttl-minutes is zero")
    void failsWhenCacheTtlZero() {
        runner.withPropertyValues("foremen.project-access.cache-ttl-minutes=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("fails startup when cache-ttl-minutes is negative")
    void failsWhenCacheTtlNegative() {
        runner.withPropertyValues("foremen.project-access.cache-ttl-minutes=-5")
                .run(context -> assertThat(context).hasFailed());
    }
}
