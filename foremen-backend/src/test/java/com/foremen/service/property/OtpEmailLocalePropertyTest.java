package com.foremen.service.property;

// Feature: FOR-03-05-otp-client-auth, Property 15: Email locale resolution falls back to Polish

import com.foremen.service.EmailLocaleResolver;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.WithNull;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 15: Email locale resolution falls back to Polish (OTP email dispatch).
 *
 * <p>For all stored user locale strings, the resolved email locale SHALL be RU when the string
 * equals {@code RU} (case-insensitively), PL when it equals {@code PL} (case-insensitively), and
 * PL for every other value including empty.
 *
 * <p>The OTP mail sender ({@code ThymeleafOtpMailSender}) resolves the recipient locale through the
 * same shared {@link EmailLocaleResolver} used by the FOR-03-02 invitation mail stack, so this
 * property re-asserts the resolver contract in the OTP context.
 *
 * <p><b>Validates: Requirements 9.3</b>
 */
class OtpEmailLocalePropertyTest {

    /** "RU" in any letter casing resolves to the Russian locale. */
    @Property(tries = 100)
    void ruInAnyCaseResolvesToRussian(@ForAll("ruVariants") String storedLocale) {
        assertThat(EmailLocaleResolver.resolve(storedLocale)).isEqualTo(EmailLocaleResolver.RU);
    }

    /** "PL" in any letter casing resolves to the Polish locale. */
    @Property(tries = 100)
    void plInAnyCaseResolvesToPolish(@ForAll("plVariants") String storedLocale) {
        assertThat(EmailLocaleResolver.resolve(storedLocale)).isEqualTo(EmailLocaleResolver.PL);
    }

    /** Every value other than RU (including null, empty, whitespace, unsupported codes) falls back to PL. */
    @Property(tries = 100)
    void everyOtherValueFallsBackToPolish(@ForAll("nonRuLocales") @WithNull(0.2) String storedLocale) {
        assertThat(EmailLocaleResolver.resolve(storedLocale)).isEqualTo(EmailLocaleResolver.PL);
    }

    /** Resolution is total: the result is always exactly one of the two supported locales. */
    @Property(tries = 100)
    void resolutionIsAlwaysPlOrRu(@ForAll("anyLocaleString") @WithNull(0.15) String storedLocale) {
        Locale resolved = EmailLocaleResolver.resolve(storedLocale);
        assertThat(resolved).isIn(EmailLocaleResolver.PL, EmailLocaleResolver.RU);
    }

    /** "RU" in arbitrary letter casing. */
    @Provide
    Arbitrary<String> ruVariants() {
        return caseVariants("ru");
    }

    /** "PL" in arbitrary letter casing. */
    @Provide
    Arbitrary<String> plVariants() {
        return caseVariants("pl");
    }

    private Arbitrary<String> caseVariants(String base) {
        Arbitrary<Boolean> upper0 = Arbitraries.of(true, false);
        Arbitrary<Boolean> upper1 = Arbitraries.of(true, false);
        return Combinators.combine(upper0, upper1).as((u0, u1) -> {
            char c0 = u0 ? Character.toUpperCase(base.charAt(0)) : Character.toLowerCase(base.charAt(0));
            char c1 = u1 ? Character.toUpperCase(base.charAt(1)) : Character.toLowerCase(base.charAt(1));
            return "" + c0 + c1;
        });
    }

    /** Non-RU strings: explicit codes plus arbitrary strings that do not equal "ru". */
    @Provide
    Arbitrary<String> nonRuLocales() {
        Arbitrary<String> fixed = Arbitraries.of("pl", "PL", "en", "EN", "de", "fr", "", "  ", "russian", "r", "rus");
        Arbitrary<String> random = Arbitraries.strings().ofMaxLength(10)
                .filter(s -> !s.trim().equalsIgnoreCase("ru"));
        return Arbitraries.oneOf(fixed, random);
    }

    /** Any locale string including pl/PL/ru/RU/en/empty and arbitrary strings. */
    @Provide
    Arbitrary<String> anyLocaleString() {
        Arbitrary<String> fixed = Arbitraries.of("pl", "PL", "ru", "RU", "en", "", "  Ru ", " pl ");
        Arbitrary<String> random = Arbitraries.strings().ofMaxLength(12);
        return Arbitraries.oneOf(fixed, random);
    }
}
