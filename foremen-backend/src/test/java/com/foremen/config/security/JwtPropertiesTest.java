package com.foremen.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Example tests for {@link JwtProperties} binding: default values and fail-fast validation.
 *
 * <p>Uses {@link ApplicationContextRunner} to bind the {@code foremen.jwt.*} properties and
 * verify:
 * <ul>
 *   <li>Defaults 30 minutes / 7 days apply when the lifetime properties are unset
 *       (Requirements 14.1, 14.2).</li>
 *   <li>Startup fails for non-positive or non-numeric lifetimes (Requirement 14.5).</li>
 * </ul>
 */
@DisplayName("JwtProperties")
class JwtPropertiesTest {

    private static final String SECRET = "a-test-signing-secret-at-least-32-bytes-long";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Configuration
    @EnableConfigurationProperties(JwtProperties.class)
    static class TestConfig {
    }

    // --- Requirements 14.1, 14.2: defaults when lifetimes are unset ---

    @Test
    @DisplayName("applies defaults of 30 minutes and 7 days when lifetimes are unset")
    void appliesDefaultsWhenLifetimesUnset() {
        runner.withPropertyValues("foremen.jwt.secret=" + SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    JwtProperties props = context.getBean(JwtProperties.class);
                    assertThat(props.accessTtlMinutes()).isEqualTo(30);
                    assertThat(props.refreshTtlDays()).isEqualTo(7);
                    assertThat(props.secret()).isEqualTo(SECRET);
                });
    }

    @Test
    @DisplayName("binds explicit positive lifetimes")
    void bindsExplicitPositiveLifetimes() {
        runner.withPropertyValues(
                        "foremen.jwt.secret=" + SECRET,
                        "foremen.jwt.access-ttl-minutes=45",
                        "foremen.jwt.refresh-ttl-days=14")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    JwtProperties props = context.getBean(JwtProperties.class);
                    assertThat(props.accessTtlMinutes()).isEqualTo(45);
                    assertThat(props.refreshTtlDays()).isEqualTo(14);
                });
    }

    // --- Requirements 7.1, 7.2: client TTL defaults when unset ---

    @Test
    @DisplayName("applies client defaults of 120 minutes and 30 days when client lifetimes are unset")
    void appliesClientDefaultsWhenLifetimesUnset() {
        runner.withPropertyValues("foremen.jwt.secret=" + SECRET)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    JwtProperties props = context.getBean(JwtProperties.class);
                    assertThat(props.clientAccessTtlMinutes()).isEqualTo(120);
                    assertThat(props.clientRefreshTtlDays()).isEqualTo(30);
                });
    }

    @Test
    @DisplayName("binds explicit positive client lifetimes")
    void bindsExplicitPositiveClientLifetimes() {
        runner.withPropertyValues(
                        "foremen.jwt.secret=" + SECRET,
                        "foremen.jwt.client-access-ttl-minutes=240",
                        "foremen.jwt.client-refresh-ttl-days=60")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    JwtProperties props = context.getBean(JwtProperties.class);
                    assertThat(props.clientAccessTtlMinutes()).isEqualTo(240);
                    assertThat(props.clientRefreshTtlDays()).isEqualTo(60);
                });
    }

    // --- Requirement 7.5: fail-fast on non-positive / non-numeric client lifetimes ---

    @Test
    @DisplayName("fails startup when client-access-ttl-minutes is zero")
    void failsWhenClientAccessTtlZero() {
        runner.withPropertyValues(
                        "foremen.jwt.secret=" + SECRET,
                        "foremen.jwt.client-access-ttl-minutes=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("fails startup when client-access-ttl-minutes is negative")
    void failsWhenClientAccessTtlNegative() {
        runner.withPropertyValues(
                        "foremen.jwt.secret=" + SECRET,
                        "foremen.jwt.client-access-ttl-minutes=-5")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("fails startup when client-refresh-ttl-days is zero")
    void failsWhenClientRefreshTtlZero() {
        runner.withPropertyValues(
                        "foremen.jwt.secret=" + SECRET,
                        "foremen.jwt.client-refresh-ttl-days=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("fails startup when client-refresh-ttl-days is negative")
    void failsWhenClientRefreshTtlNegative() {
        runner.withPropertyValues(
                        "foremen.jwt.secret=" + SECRET,
                        "foremen.jwt.client-refresh-ttl-days=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("fails startup when client-access-ttl-minutes is non-numeric")
    void failsWhenClientAccessTtlNonNumeric() {
        runner.withPropertyValues(
                        "foremen.jwt.secret=" + SECRET,
                        "foremen.jwt.client-access-ttl-minutes=abc")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("fails startup when client-refresh-ttl-days is non-numeric")
    void failsWhenClientRefreshTtlNonNumeric() {
        runner.withPropertyValues(
                        "foremen.jwt.secret=" + SECRET,
                        "foremen.jwt.client-refresh-ttl-days=not-a-number")
                .run(context -> assertThat(context).hasFailed());
    }

    // --- Requirement 14.5: fail-fast on non-positive lifetimes ---

    @Test
    @DisplayName("fails startup when access-ttl-minutes is zero")
    void failsWhenAccessTtlZero() {
        runner.withPropertyValues(
                        "foremen.jwt.secret=" + SECRET,
                        "foremen.jwt.access-ttl-minutes=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("fails startup when access-ttl-minutes is negative")
    void failsWhenAccessTtlNegative() {
        runner.withPropertyValues(
                        "foremen.jwt.secret=" + SECRET,
                        "foremen.jwt.access-ttl-minutes=-5")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("fails startup when refresh-ttl-days is zero")
    void failsWhenRefreshTtlZero() {
        runner.withPropertyValues(
                        "foremen.jwt.secret=" + SECRET,
                        "foremen.jwt.refresh-ttl-days=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("fails startup when refresh-ttl-days is negative")
    void failsWhenRefreshTtlNegative() {
        runner.withPropertyValues(
                        "foremen.jwt.secret=" + SECRET,
                        "foremen.jwt.refresh-ttl-days=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    // --- Requirement 14.5: fail-fast on non-numeric lifetimes ---

    @Test
    @DisplayName("fails startup when access-ttl-minutes is non-numeric")
    void failsWhenAccessTtlNonNumeric() {
        runner.withPropertyValues(
                        "foremen.jwt.secret=" + SECRET,
                        "foremen.jwt.access-ttl-minutes=abc")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("fails startup when refresh-ttl-days is non-numeric")
    void failsWhenRefreshTtlNonNumeric() {
        runner.withPropertyValues(
                        "foremen.jwt.secret=" + SECRET,
                        "foremen.jwt.refresh-ttl-days=not-a-number")
                .run(context -> assertThat(context).hasFailed());
    }

    // --- @NotBlank secret validation ---

    @Test
    @DisplayName("fails startup when secret is blank")
    void failsWhenSecretBlank() {
        runner.withPropertyValues("foremen.jwt.secret=")
                .run(context -> assertThat(context).hasFailed());
    }
}
