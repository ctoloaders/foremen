package com.foremen.service.property;

// Feature: FOR-03-05-otp-client-auth, Property 15: Email locale resolution falls back to Polish
// FOR-06-12 (R12.4): adapted to the Locale-typed EmailLocaleResolver after the UserEntity.locale
// String -> Locale migration. Stored-string normalization now lives in LocaleAttributeConverter;
// this resolver operates on the already-resolved Locale.

import com.foremen.service.EmailLocaleResolver;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.WithNull;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 15: Email locale resolution falls back to Polish (OTP email dispatch).
 *
 * <p>For all user {@link Locale} values, the resolved email locale SHALL be RU when the locale's
 * language is Russian, and PL for every other value including {@code null} and unsupported locales.
 *
 * <p>The OTP mail sender ({@code ThymeleafOtpMailSender}) resolves the recipient locale through the
 * same shared {@link EmailLocaleResolver} used by the FOR-03-02 invitation mail stack, so this
 * property re-asserts the resolver contract in the OTP context.
 *
 * <p><b>Validates: Requirements 9.3, 12.4</b>
 */
class OtpEmailLocalePropertyTest {

    /** A Russian-language locale resolves to the Russian locale. */
    @Property(tries = 100)
    void russianLanguageResolvesToRussian(@ForAll("ruLocales") Locale userLocale) {
        assertThat(EmailLocaleResolver.resolve(userLocale)).isEqualTo(EmailLocaleResolver.RU);
    }

    /** A Polish-language locale resolves to the Polish locale. */
    @Property(tries = 100)
    void polishLanguageResolvesToPolish(@ForAll("plLocales") Locale userLocale) {
        assertThat(EmailLocaleResolver.resolve(userLocale)).isEqualTo(EmailLocaleResolver.PL);
    }

    /** Every non-Russian locale (including null and unsupported languages) falls back to PL. */
    @Property(tries = 100)
    void everyOtherLocaleFallsBackToPolish(@ForAll("nonRuLocales") @WithNull(0.2) Locale userLocale) {
        assertThat(EmailLocaleResolver.resolve(userLocale)).isEqualTo(EmailLocaleResolver.PL);
    }

    /** Resolution is total: the result is always exactly one of the two supported locales. */
    @Property(tries = 100)
    void resolutionIsAlwaysPlOrRu(@ForAll("anyLocale") @WithNull(0.15) Locale userLocale) {
        Locale resolved = EmailLocaleResolver.resolve(userLocale);
        assertThat(resolved).isIn(EmailLocaleResolver.PL, EmailLocaleResolver.RU);
    }

    /** Russian locales: plain {@code ru} plus regional variants. */
    @Provide
    Arbitrary<Locale> ruLocales() {
        return Arbitraries.of("ru", "ru-RU", "ru-BY", "ru-UA").map(Locale::forLanguageTag);
    }

    /** Polish locales: plain {@code pl} plus a regional variant. */
    @Provide
    Arbitrary<Locale> plLocales() {
        return Arbitraries.of("pl", "pl-PL").map(Locale::forLanguageTag);
    }

    /** Non-Russian locales: PL and a spread of unsupported languages. */
    @Provide
    Arbitrary<Locale> nonRuLocales() {
        return Arbitraries.of("pl", "pl-PL", "en", "en-US", "de", "fr", "es", "uk")
                .map(Locale::forLanguageTag);
    }

    /** Any locale including ru/pl and arbitrary language tags. */
    @Provide
    Arbitrary<Locale> anyLocale() {
        Arbitrary<Locale> fixed =
                Arbitraries.of("ru", "ru-RU", "pl", "pl-PL", "en", "de", "fr").map(Locale::forLanguageTag);
        Arbitrary<Locale> random = Arbitraries.strings()
                .withCharRange('a', 'z').ofMinLength(2).ofMaxLength(2)
                .map(Locale::forLanguageTag);
        return Arbitraries.oneOf(fixed, random);
    }
}
