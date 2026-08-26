package com.foremen.controller.property;

import com.foremen.controller.model.DisplayPreferencesRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import net.jqwik.api.*;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 11: Backend validation rejects invalid preference values
 *
 * For any string value of themeMode not in {"dark", "light", "system"},
 * or colorScheme not in the defined preset names,
 * or fontSize not in {"sm", "default", "lg", "xl"},
 * the backend validation SHALL reject the request with constraint violations
 * indicating which fields failed.
 *
 * Validates: Requirements 6.3, 6.4
 */
@Tag("Feature: FOR-02-08-theme-settings, Property 11: Backend validation rejects invalid preference values")
class DisplayPreferencesValidationPropertyTest {

    private static final Set<String> VALID_THEME_MODES = Set.of("dark", "light", "system");
    private static final Set<String> VALID_COLOR_SCHEMES = Set.of(
            "zinc", "slate", "stone", "gray", "neutral", "blue", "green", "orange", "red"
    );
    private static final Set<String> VALID_FONT_SIZES = Set.of("sm", "default", "lg", "xl");

    private final Validator validator;

    DisplayPreferencesValidationPropertyTest() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            this.validator = factory.getValidator();
        }
    }

    // --- Property: any invalid themeMode is rejected ---

    @Property(tries = 100)
    void invalidThemeModeIsRejected(@ForAll("invalidThemeModes") String invalidThemeMode) {
        DisplayPreferencesRequest request = new DisplayPreferencesRequest(
                invalidThemeMode, "zinc", "default"
        );

        Set<ConstraintViolation<DisplayPreferencesRequest>> violations = validator.validate(request);

        assertThat(violations)
                .isNotEmpty()
                .anyMatch(v -> v.getPropertyPath().toString().equals("themeMode"));
    }

    // --- Property: any invalid colorScheme is rejected ---

    @Property(tries = 100)
    void invalidColorSchemeIsRejected(@ForAll("invalidColorSchemes") String invalidColorScheme) {
        DisplayPreferencesRequest request = new DisplayPreferencesRequest(
                "dark", invalidColorScheme, "default"
        );

        Set<ConstraintViolation<DisplayPreferencesRequest>> violations = validator.validate(request);

        assertThat(violations)
                .isNotEmpty()
                .anyMatch(v -> v.getPropertyPath().toString().equals("colorScheme"));
    }

    // --- Property: any invalid fontSize is rejected ---

    @Property(tries = 100)
    void invalidFontSizeIsRejected(@ForAll("invalidFontSizes") String invalidFontSize) {
        DisplayPreferencesRequest request = new DisplayPreferencesRequest(
                "dark", "zinc", invalidFontSize
        );

        Set<ConstraintViolation<DisplayPreferencesRequest>> violations = validator.validate(request);

        assertThat(violations)
                .isNotEmpty()
                .anyMatch(v -> v.getPropertyPath().toString().equals("fontSize"));
    }

    // --- Property: any combination of all-invalid values is rejected ---

    @Property(tries = 100)
    void allInvalidFieldsAreRejected(
            @ForAll("invalidThemeModes") String invalidThemeMode,
            @ForAll("invalidColorSchemes") String invalidColorScheme,
            @ForAll("invalidFontSizes") String invalidFontSize
    ) {
        DisplayPreferencesRequest request = new DisplayPreferencesRequest(
                invalidThemeMode, invalidColorScheme, invalidFontSize
        );

        Set<ConstraintViolation<DisplayPreferencesRequest>> violations = validator.validate(request);

        assertThat(violations).hasSizeGreaterThanOrEqualTo(3);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("themeMode"));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("colorScheme"));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("fontSize"));
    }

    // --- Property: valid values pass validation ---

    @Property(tries = 100)
    void validPreferencesPassValidation(
            @ForAll("validThemeModes") String themeMode,
            @ForAll("validColorSchemes") String colorScheme,
            @ForAll("validFontSizes") String fontSize
    ) {
        DisplayPreferencesRequest request = new DisplayPreferencesRequest(
                themeMode, colorScheme, fontSize
        );

        Set<ConstraintViolation<DisplayPreferencesRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    // --- Providers ---

    @Provide
    Arbitrary<String> invalidThemeModes() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(50)
                .filter(s -> !VALID_THEME_MODES.contains(s));
    }

    @Provide
    Arbitrary<String> invalidColorSchemes() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(50)
                .filter(s -> !VALID_COLOR_SCHEMES.contains(s));
    }

    @Provide
    Arbitrary<String> invalidFontSizes() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(50)
                .filter(s -> !VALID_FONT_SIZES.contains(s));
    }

    @Provide
    Arbitrary<String> validThemeModes() {
        return Arbitraries.of(VALID_THEME_MODES.toArray(new String[0]));
    }

    @Provide
    Arbitrary<String> validColorSchemes() {
        return Arbitraries.of(VALID_COLOR_SCHEMES.toArray(new String[0]));
    }

    @Provide
    Arbitrary<String> validFontSizes() {
        return Arbitraries.of(VALID_FONT_SIZES.toArray(new String[0]));
    }
}
