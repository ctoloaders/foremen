package com.foremen.service.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Optional;

import static com.foremen.config.persistence.LocaleAttributeConverter.PL;
import static com.foremen.config.persistence.LocaleAttributeConverter.RU;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-06-12 task 2.3 (Requirements 10.3, 6.1) — unit tests for the
 * {@link DisabledMessageTranslationService}, the fallback {@link MessageTranslationService} wired
 * when no translation provider is configured.
 *
 * <p>With no provider:
 * <ul>
 *   <li>{@link DisabledMessageTranslationService#translate} always returns {@link Optional#empty()}
 *       — the message stays available in its original locale only (R6.3);</li>
 *   <li>{@link DisabledMessageTranslationService#detectLocale} returns the author-preferred locale
 *       when it is supported, and PL otherwise ({@code null}/unsupported) (R10.3);</li>
 *   <li>{@link DisabledMessageTranslationService#isConfigured()} is {@code false}.</li>
 * </ul>
 */
class DisabledMessageTranslationServiceTest {

    private final DisabledMessageTranslationService service = new DisabledMessageTranslationService();

    @Test
    @DisplayName("translate always returns Optional.empty (no provider)")
    void translateReturnsEmpty() {
        assertThat(service.translate("dzień dobry", PL, RU)).isEmpty();
        assertThat(service.translate("привет", RU, PL)).isEmpty();
    }

    @Test
    @DisplayName("detectLocale returns the preferred locale when it is supported")
    void detectLocaleReturnsSupportedPreferred() {
        assertThat(service.detectLocale("some text", RU)).isEqualTo(RU);
        assertThat(service.detectLocale("some text", PL)).isEqualTo(PL);
    }

    @Test
    @DisplayName("detectLocale falls back to PL when the preferred locale is null")
    void detectLocaleFallsBackToPlForNull() {
        assertThat(service.detectLocale("some text", null)).isEqualTo(PL);
    }

    @Test
    @DisplayName("detectLocale falls back to PL when the preferred locale is unsupported")
    void detectLocaleFallsBackToPlForUnsupported() {
        assertThat(service.detectLocale("some text", Locale.ENGLISH)).isEqualTo(PL);
    }

    @Test
    @DisplayName("isConfigured is false for the disabled fallback")
    void isConfiguredIsFalse() {
        assertThat(service.isConfigured()).isFalse();
    }
}
