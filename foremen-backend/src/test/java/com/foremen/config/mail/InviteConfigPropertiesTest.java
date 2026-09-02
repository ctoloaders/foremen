package com.foremen.config.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Example tests for the invite configuration binding via {@link MailConfig}, which
 * {@code @EnableConfigurationProperties} both {@link InviteProperties} ({@code foremen.invite}) and
 * {@link MailInviteProperties} ({@code foremen.mail}).
 *
 * <p>Uses {@link ApplicationContextRunner} to bind the properties and verify:
 * <ul>
 *   <li>{@code foremen.invite.ttl-hours} defaults to 72 when unset (Requirements 7.1, 7.5).</li>
 *   <li>{@code foremen.mail.invite-base-url} defaults to the localhost set-password URL — the
 *       {@code application.yml} placeholder default {@code MAIL_INVITE_BASE_URL} —
 *       (Requirements 7.1, 7.5).</li>
 *   <li>Startup fails for an out-of-range or non-integer TTL (Requirements 7.2 / 7.6).</li>
 *   <li>Startup fails for an empty, blank, or non-http(s) invite base URL (Requirements 7.2, 7.6).</li>
 * </ul>
 */
@DisplayName("Invite configuration properties")
class InviteConfigPropertiesTest {

    private static final String DEFAULT_INVITE_BASE_URL = "http://localhost:3000/auth/set-password";

    /**
     * Mirrors the {@code application.yml} placeholder {@code ${MAIL_INVITE_BASE_URL:<default>}} so
     * that, with the environment variable unset, the configured base URL falls back to the
     * documented localhost set-password default (Requirements 7.1, 7.5).
     */
    private static final String INVITE_BASE_URL_PLACEHOLDER =
            "foremen.mail.invite-base-url=${MAIL_INVITE_BASE_URL:" + DEFAULT_INVITE_BASE_URL + "}";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(MailConfig.class);

    // --- Requirements 7.1, 7.5: defaults when properties are unset ---

    @Test
    @DisplayName("ttl-hours defaults to 72 when unset")
    void ttlHoursDefaultsTo72WhenUnset() {
        runner.withPropertyValues("foremen.mail.invite-base-url=" + DEFAULT_INVITE_BASE_URL)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    InviteProperties props = context.getBean(InviteProperties.class);
                    assertThat(props.ttlHours()).isEqualTo(72);
                });
    }

    @Test
    @DisplayName("invite-base-url defaults to the localhost set-password URL when the env var is unset")
    void inviteBaseUrlDefaultsToLocalhostSetPassword() {
        runner.withPropertyValues(INVITE_BASE_URL_PLACEHOLDER)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MailInviteProperties props = context.getBean(MailInviteProperties.class);
                    assertThat(props.inviteBaseUrl()).isEqualTo(DEFAULT_INVITE_BASE_URL);
                });
    }

    @Test
    @DisplayName("binds explicit valid values for both properties")
    void bindsExplicitValidValues() {
        runner.withPropertyValues(
                        "foremen.invite.ttl-hours=168",
                        "foremen.mail.invite-base-url=https://app.example.com/auth/set-password")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(InviteProperties.class).ttlHours()).isEqualTo(168);
                    assertThat(context.getBean(MailInviteProperties.class).inviteBaseUrl())
                            .isEqualTo("https://app.example.com/auth/set-password");
                });
    }

    // --- Requirement 7.6: fail-fast on out-of-range TTL ---

    @Test
    @DisplayName("fails startup when ttl-hours is zero (below range)")
    void failsWhenTtlHoursZero() {
        runner.withPropertyValues(
                        "foremen.mail.invite-base-url=" + DEFAULT_INVITE_BASE_URL,
                        "foremen.invite.ttl-hours=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("fails startup when ttl-hours exceeds 8760 (above range)")
    void failsWhenTtlHoursAboveRange() {
        runner.withPropertyValues(
                        "foremen.mail.invite-base-url=" + DEFAULT_INVITE_BASE_URL,
                        "foremen.invite.ttl-hours=9000")
                .run(context -> assertThat(context).hasFailed());
    }

    // --- Requirement 7.6: fail-fast on non-integer TTL ---

    @Test
    @DisplayName("fails startup when ttl-hours is non-integer")
    void failsWhenTtlHoursNonInteger() {
        runner.withPropertyValues(
                        "foremen.mail.invite-base-url=" + DEFAULT_INVITE_BASE_URL,
                        "foremen.invite.ttl-hours=abc")
                .run(context -> assertThat(context).hasFailed());
    }

    // --- Requirement 7.2: fail-fast on invalid invite base URL ---

    @Test
    @DisplayName("fails startup when invite-base-url is empty")
    void failsWhenInviteBaseUrlEmpty() {
        runner.withPropertyValues("foremen.mail.invite-base-url=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("fails startup when invite-base-url is blank")
    void failsWhenInviteBaseUrlBlank() {
        runner.withPropertyValues("foremen.mail.invite-base-url=   ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("fails startup when invite-base-url is not an http/https URL")
    void failsWhenInviteBaseUrlNotHttp() {
        runner.withPropertyValues("foremen.mail.invite-base-url=ftp://example.com/set-password")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("fails startup when invite-base-url is not an absolute URL")
    void failsWhenInviteBaseUrlNotAbsolute() {
        runner.withPropertyValues("foremen.mail.invite-base-url=/auth/set-password")
                .run(context -> assertThat(context).hasFailed());
    }
}
