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
 * Unit test asserting PL/RU parity of the FOR-06-03 (procurement intake + OCR) message-bundle keys.
 *
 * <p>Every message code introduced by FOR-06-03 has a non-blank entry in BOTH the Polish base
 * bundle ({@code messages.properties}) and the Russian bundle ({@code messages_ru.properties}), so
 * no raw key is ever surfaced to the user regardless of locale:
 *
 * <ul>
 *   <li>the R2 upload-rule rejections +
 *       the R7.1 stage gate ({@code error.procurement.upload.*}, {@code error.procurement.stage.notActive})
 *       thrown by {@code ProcurementDocumentService}, and</li>
 *   <li>the R3 OCR failure reasons ({@code procurement.ocr.disabled}, {@code .noLines},
 *       {@code .failed}) stored as the document's {@code failureReason} and surfaced on
 *       {@code FAILED}/{@code UPLOADED}.</li>
 * </ul>
 *
 * <p>Mirrors the bundle-loading approach of {@code ScheduleGranularityMessagesParityTest}.
 *
 * <p>Validates: Requirements 10.3
 */
class ProcurementMessagesParityTest {

    /** Polish base bundle. */
    private static final String PL_BUNDLE = "messages.properties";
    /** Russian bundle. */
    private static final String RU_BUNDLE = "messages_ru.properties";

    private static final String[] FOR0603_KEYS = {
            "error.procurement.upload.storageUnavailable",
            "error.procurement.upload.unsupportedType",
            "error.procurement.upload.tooLarge",
            "error.procurement.stage.notActive",
            "procurement.ocr.disabled",
            "procurement.ocr.noLines",
            "procurement.ocr.failed",
            // Amendment A1 (R18.1/R18.2) — stage-specific async-OCR failure reasons.
            "procurement.ocr.failed.rasterize",
            "procurement.ocr.failed.vision",
            "procurement.ocr.failed.noText",
            "procurement.ocr.failed.parse",
            "procurement.ocr.failed.storage"
    };

    @ParameterizedTest
    @ValueSource(strings = {
            "error.procurement.upload.storageUnavailable",
            "error.procurement.upload.unsupportedType",
            "error.procurement.upload.tooLarge",
            "error.procurement.stage.notActive",
            "procurement.ocr.disabled",
            "procurement.ocr.noLines",
            "procurement.ocr.failed",
            "procurement.ocr.failed.rasterize",
            "procurement.ocr.failed.vision",
            "procurement.ocr.failed.noText",
            "procurement.ocr.failed.parse",
            "procurement.ocr.failed.storage"
    })
    @DisplayName("every FOR-06-03 message key has a non-blank PL entry that is not the raw key")
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
            "error.procurement.upload.storageUnavailable",
            "error.procurement.upload.unsupportedType",
            "error.procurement.upload.tooLarge",
            "error.procurement.stage.notActive",
            "procurement.ocr.disabled",
            "procurement.ocr.noLines",
            "procurement.ocr.failed",
            "procurement.ocr.failed.rasterize",
            "procurement.ocr.failed.vision",
            "procurement.ocr.failed.noText",
            "procurement.ocr.failed.parse",
            "procurement.ocr.failed.storage"
    })
    @DisplayName("every FOR-06-03 message key has a non-blank RU entry that is not the raw key")
    void keyHasNonBlankRuEntry(String code) throws IOException {
        Properties ru = loadBundle(RU_BUNDLE);

        assertThat(ru.getProperty(code))
                .as("RU bundle '%s' must contain a non-blank entry for '%s'", RU_BUNDLE, code)
                .isNotNull()
                .isNotBlank()
                .isNotEqualTo(code);
    }

    @Test
    @DisplayName("all FOR-06-03 message keys are present in both bundles at once (identical key set)")
    void allKeysPresentInBothBundles() throws IOException {
        Properties pl = loadBundle(PL_BUNDLE);
        Properties ru = loadBundle(RU_BUNDLE);

        for (String code : FOR0603_KEYS) {
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
        try (InputStream in = ProcurementMessagesParityTest.class
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
