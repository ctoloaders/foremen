package com.foremen.config.i18n;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: FOR-03-02-user-invitation, Task 12.3: Request-locale invite error resolution.
 *
 * The {@link MessageResolver} used by {@code ForemenControllerAdvice} resolves an invite
 * error message code according to the request locale: Polish for {@code pl} and Russian for
 * {@code ru} (Requirement 8.3). A code present only in the PL base bundle
 * ({@code messages.properties}) falls back to the Polish text when RU is requested
 * (Requirement 8.5).
 *
 * <p>Mirrors the existing {@link MessageResolverTest} setup: a real
 * {@link ResourceBundleMessageSource} over the {@code messages} basename wrapped by
 * {@link MessageResolver}, exactly as wired in production and in the auth integration test.
 *
 * Validates: Requirements 8.3, 8.5
 */
class InviteMessageResolutionTest {

    private static final Locale PL = Locale.of("pl");
    private static final Locale RU = Locale.of("ru");

    private MessageResolver messageResolver;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setUseCodeAsDefaultMessage(true);
        messageResolver = new MessageResolver(messageSource);
    }

    @Test
    @DisplayName("invite error code resolves to the Polish text for a PL request locale (8.3)")
    void inviteCodeResolvesToPolishForPlLocale() {
        String result = messageResolver.resolve("error.invite.token.invalid", null, PL);

        assertThat(result).isEqualTo("Zaproszenie jest nieprawidłowe.");
    }

    @Test
    @DisplayName("invite error code resolves to the Russian text for a RU request locale (8.3)")
    void inviteCodeResolvesToRussianForRuLocale() {
        String result = messageResolver.resolve("error.invite.token.invalid", null, RU);

        assertThat(result).isEqualTo("Приглашение недействительно.");
    }

    @Test
    @DisplayName("same invite code yields different text per locale, confirming per-request resolution (8.3)")
    void inviteCodeResolvesDifferentlyPerLocale() {
        String pl = messageResolver.resolve("error.invite.token.used", null, PL);
        String ru = messageResolver.resolve("error.invite.token.used", null, RU);

        assertThat(pl).isEqualTo("To zaproszenie zostało już wykorzystane.");
        assertThat(ru).isEqualTo("Это приглашение уже было использовано.");
        assertThat(pl).isNotEqualTo(ru);
    }

    @Test
    @DisplayName("PL-only base code falls back to the Polish text when RU is requested (8.5)")
    void baseOnlyCodeFallsBackToPolishForRuLocale() {
        // error.user.email.already.exists exists only in the PL base bundle
        // (messages.properties) and has no RU override in messages_ru.properties,
        // so a RU request falls back to the Polish base text.
        String expectedBase = messageResolver.resolve("error.user.email.already.exists",
                new Object[]{"user@example.com"}, PL);

        String ruResult = messageResolver.resolve("error.user.email.already.exists",
                new Object[]{"user@example.com"}, RU);

        assertThat(ruResult).isEqualTo(expectedBase);
        assertThat(ruResult).contains("user@example.com");
    }
}
