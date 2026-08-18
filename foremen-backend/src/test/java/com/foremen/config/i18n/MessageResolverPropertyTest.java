package com.foremen.config.i18n;

import net.jqwik.api.*;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property 5: Message Resolution Always Non-Empty
 *
 * For any message code string (whether present in the bundle or not) and any locale,
 * the MessageResolver.resolve() method SHALL return a non-null string with length > 0.
 * When the code exists in the bundle, it returns the localized message; when it does not
 * exist, it returns the code itself.
 *
 * Validates: Requirements 4.3, 8.2, 8.6
 */
@Tag("Feature: FOR-01-03-exception, Property 5: Message Resolution Always Non-Empty")
class MessageResolverPropertyTest {

    private final MessageResolver messageResolver;

    MessageResolverPropertyTest() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setUseCodeAsDefaultMessage(true);
        this.messageResolver = new MessageResolver(messageSource);
    }

    @Property(tries = 100)
    void resolveKnownCodeReturnsNonEmptyMessage(
            @ForAll("knownCodes") String code,
            @ForAll("locales") Locale locale) {

        String result = messageResolver.resolve(code, new Object[]{}, locale);

        assertNotNull(result, "Resolved message should not be null for known code: " + code);
        assertFalse(result.isEmpty(), "Resolved message should not be empty for known code: " + code);
    }

    @Property(tries = 100)
    void resolveUnknownCodeReturnsNonEmptyFallback(
            @ForAll("randomCodes") String code,
            @ForAll("locales") Locale locale) {

        String result = messageResolver.resolve(code, null, locale);

        assertNotNull(result, "Resolved message should not be null for unknown code: " + code);
        assertFalse(result.isEmpty(), "Resolved message should not be empty for unknown code: " + code);
        assertEquals(code, result, "Unknown code should fall back to the code itself");
    }

    @Provide
    Arbitrary<String> knownCodes() {
        return Arbitraries.of(
                "error.data.integrity",
                "error.access.denied",
                "error.internal",
                "error.validation"
        );
    }

    @Provide
    Arbitrary<String> randomCodes() {
        // Generate random dot-separated alphanumeric strings that don't exist in the bundle
        Arbitrary<String> segment = Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(10);

        return segment.flatMap(first ->
                segment.flatMap(second ->
                        segment.map(third -> first + "." + second + "." + third)
                )
        );
    }

    @Provide
    Arbitrary<Locale> locales() {
        return Arbitraries.of(
                Locale.of("pl"),
                Locale.of("ru"),
                Locale.ENGLISH,
                Locale.FRENCH,
                Locale.GERMAN,
                Locale.of("uk"),
                Locale.of("es")
        );
    }
}
