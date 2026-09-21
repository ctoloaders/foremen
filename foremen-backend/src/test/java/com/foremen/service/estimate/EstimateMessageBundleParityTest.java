package com.foremen.service.estimate;

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
 * Unit test asserting PL/RU parity of the FOR-05-03 estimate message-bundle keys.
 *
 * <p>Property 13: i18n parity — every message key added for FOR-05-03
 * ({@code error.estimate.already.exists}, {@code error.estimate.locked},
 * {@code error.estimate.qty.negative}, {@code error.estimate.room.cross.project}) has a
 * non-blank entry in both the Polish base bundle ({@code messages.properties}) and the
 * Russian bundle ({@code messages_ru.properties}), so no raw key is ever surfaced to the
 * user regardless of locale.
 *
 * <p>Mirrors the bundle-loading approach of
 * {@code AuthorizationMessageLocalizationPropertyTest} (FOR-03-03).
 *
 * <p>Validates: Requirements 11.1, 11.2, 11.3
 */
class EstimateMessageBundleParityTest {

    /** Polish base bundle. */
    private static final String PL_BUNDLE = "messages.properties";
    /** Russian bundle. */
    private static final String RU_BUNDLE = "messages_ru.properties";

    @ParameterizedTest
    @ValueSource(strings = {
            "error.estimate.already.exists",
            "error.estimate.locked",
            "error.estimate.qty.negative",
            "error.estimate.room.cross.project"
    })
    @DisplayName("every FOR-05-03 estimate message key has a non-blank PL entry")
    void estimateKeyHasNonBlankPlEntry(String code) throws IOException {
        Properties pl = loadBundle(PL_BUNDLE);

        assertThat(pl.getProperty(code))
                .as("PL bundle '%s' must contain a non-blank entry for '%s'", PL_BUNDLE, code)
                .isNotNull()
                .isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "error.estimate.already.exists",
            "error.estimate.locked",
            "error.estimate.qty.negative",
            "error.estimate.room.cross.project"
    })
    @DisplayName("every FOR-05-03 estimate message key has a non-blank RU entry")
    void estimateKeyHasNonBlankRuEntry(String code) throws IOException {
        Properties ru = loadBundle(RU_BUNDLE);

        assertThat(ru.getProperty(code))
                .as("RU bundle '%s' must contain a non-blank entry for '%s'", RU_BUNDLE, code)
                .isNotNull()
                .isNotBlank();
    }

    /** Loads a message bundle from the classpath as UTF-8 properties. */
    private static Properties loadBundle(String bundleName) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = EstimateMessageBundleParityTest.class
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
