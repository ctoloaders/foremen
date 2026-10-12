package com.foremen.config.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test asserting PL/RU parity of the FOR-05-10b (Gantt granularity) message-bundle keys.
 *
 * <p>Every {@code error.*} code introduced by FOR-05-10b
 * ({@code error.user.workCategories.duplicate} / {@code .invalid} — task 3.1,
 * {@code error.member.workCategories.duplicate} / {@code .invalid} — task 3.2,
 * {@code error.schedule.assignment.invalid} — task 4.2) has a non-blank entry in BOTH the Polish
 * base bundle ({@code messages.properties}) and the Russian bundle ({@code messages_ru.properties}),
 * so no raw key is ever surfaced to the user regardless of locale.
 *
 * <p>Mirrors the bundle-loading approach of {@code FormulaMessageBundleParityTest} and
 * {@code EstimateMessageBundleParityTest}.
 *
 * <p>Validates: Requirements 9.3
 */
class ScheduleGranularityMessagesParityTest {

    /** Polish base bundle. */
    private static final String PL_BUNDLE = "messages.properties";
    /** Russian bundle. */
    private static final String RU_BUNDLE = "messages_ru.properties";

    private static final String[] FOR0510B_KEYS = {
            "error.user.workCategories.duplicate",
            "error.user.workCategories.invalid",
            "error.member.workCategories.duplicate",
            "error.member.workCategories.invalid",
            "error.schedule.assignment.invalid"
    };

    @ParameterizedTest
    @ValueSource(strings = {
            "error.user.workCategories.duplicate",
            "error.user.workCategories.invalid",
            "error.member.workCategories.duplicate",
            "error.member.workCategories.invalid",
            "error.schedule.assignment.invalid"
    })
    @DisplayName("every FOR-05-10b error key has a non-blank PL entry that is not the raw key")
    void keyHasNonBlankPlEntry(String code) throws IOException {
        Properties pl = loadBundle(PL_BUNDLE);

        assertThat(pl.getProperty(code))
                .as("PL bundle '%s' must contain a non-blank entry for '%s'", PL_BUNDLE, code)
                .isNotNull()
                .isNotBlank()
                .isNotEqualTo(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "error.user.workCategories.duplicate",
            "error.user.workCategories.invalid",
            "error.member.workCategories.duplicate",
            "error.member.workCategories.invalid",
            "error.schedule.assignment.invalid"
    })
    @DisplayName("every FOR-05-10b error key has a non-blank RU entry that is not the raw key")
    void keyHasNonBlankRuEntry(String code) throws IOException {
        Properties ru = loadBundle(RU_BUNDLE);

        assertThat(ru.getProperty(code))
                .as("RU bundle '%s' must contain a non-blank entry for '%s'", RU_BUNDLE, code)
                .isNotNull()
                .isNotBlank()
                .isNotEqualTo(code);
    }

    @Test
    @DisplayName("all FOR-05-10b error keys are present in both bundles at once (identical key set)")
    void allKeysPresentInBothBundles() throws IOException {
        Properties pl = loadBundle(PL_BUNDLE);
        Properties ru = loadBundle(RU_BUNDLE);

        for (String code : FOR0510B_KEYS) {
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
        try (InputStream in = ScheduleGranularityMessagesParityTest.class
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
