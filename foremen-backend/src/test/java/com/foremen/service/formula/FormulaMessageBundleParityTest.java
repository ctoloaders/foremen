package com.foremen.service.formula;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test asserting PL/RU parity of the FOR-05-04 formula-engine message-bundle keys.
 *
 * <p>Every message key added for FOR-05-04's formula engine
 * ({@code error.formula.unknown.variable}, {@code error.formula.unknown.work.reference},
 * {@code error.formula.illegal.operator}, {@code error.formula.cycle},
 * {@code error.formula.division.by.zero}) has a non-blank entry in both the Polish base bundle
 * ({@code messages.properties}) and the Russian bundle ({@code messages_ru.properties}), so no
 * raw key is ever surfaced to the user regardless of locale.
 *
 * <p>Mirrors the bundle-loading approach of {@code EstimateMessageBundleParityTest} (FOR-05-03).
 *
 * <p>Validates: Requirements 2.2, 2.3, 3.3
 */
class FormulaMessageBundleParityTest {

    /** Polish base bundle. */
    private static final String PL_BUNDLE = "messages.properties";
    /** Russian bundle. */
    private static final String RU_BUNDLE = "messages_ru.properties";

    private static final String[] FORMULA_KEYS = {
            "error.formula.unknown.variable",
            "error.formula.unknown.work.reference",
            "error.formula.illegal.operator",
            "error.formula.cycle",
            "error.formula.division.by.zero"
    };

    @ParameterizedTest
    @ValueSource(strings = {
            "error.formula.unknown.variable",
            "error.formula.unknown.work.reference",
            "error.formula.illegal.operator",
            "error.formula.cycle",
            "error.formula.division.by.zero"
    })
    @DisplayName("every FOR-05-04 formula message key has a non-blank PL entry that is not the raw key")
    void formulaKeyHasNonBlankPlEntry(String code) throws IOException {
        Properties pl = loadBundle(PL_BUNDLE);

        assertThat(pl.getProperty(code))
                .as("PL bundle '%s' must contain a non-blank entry for '%s'", PL_BUNDLE, code)
                .isNotNull()
                .isNotBlank()
                .isNotEqualTo(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "error.formula.unknown.variable",
            "error.formula.unknown.work.reference",
            "error.formula.illegal.operator",
            "error.formula.cycle",
            "error.formula.division.by.zero"
    })
    @DisplayName("every FOR-05-04 formula message key has a non-blank RU entry that is not the raw key")
    void formulaKeyHasNonBlankRuEntry(String code) throws IOException {
        Properties ru = loadBundle(RU_BUNDLE);

        assertThat(ru.getProperty(code))
                .as("RU bundle '%s' must contain a non-blank entry for '%s'", RU_BUNDLE, code)
                .isNotNull()
                .isNotBlank()
                .isNotEqualTo(code);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("all five FOR-05-04 formula keys are present in both bundles at once")
    void allFormulaKeysPresentInBothBundles() throws IOException {
        Properties pl = loadBundle(PL_BUNDLE);
        Properties ru = loadBundle(RU_BUNDLE);

        for (String code : FORMULA_KEYS) {
            assertThat(pl.getProperty(code))
                    .as("PL bundle missing non-blank entry for '%s'", code)
                    .isNotNull()
                    .isNotBlank()
                    .isNotEqualTo(code);
            assertThat(ru.getProperty(code))
                    .as("RU bundle missing non-blank entry for '%s'", code)
                    .isNotNull()
                    .isNotBlank()
                    .isNotEqualTo(code);
        }
    }

    /** Loads a message bundle from the classpath as UTF-8 properties. */
    private static Properties loadBundle(String bundleName) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = FormulaMessageBundleParityTest.class
                .getClassLoader()
                .getResourceAsStream(bundleName)) {
            assertThat(in)
                    .as("Message bundle '%s' must be present on the classpath", bundleName)
                    .isNotNull();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
