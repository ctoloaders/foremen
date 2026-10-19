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
 * Unit test asserting PL/RU parity of the FOR-06-07 (client payments) message-bundle keys.
 *
 * <p>Every {@code error.payment.*} code introduced by FOR-06-07 has a non-blank entry in BOTH the
 * Polish base bundle ({@code messages.properties}) and the Russian bundle
 * ({@code messages_ru.properties}), so no raw key is ever surfaced to the user regardless of locale:
 *
 * <ul>
 *   <li>the generator rejections ({@code error.payment.generator.*}) thrown by
 *       {@code TrancheGenerator} / {@code PaymentScheduleService.generate},</li>
 *   <li>the schedule-lifecycle rejections ({@code error.payment.schedule.*}) thrown by
 *       {@code PaymentScheduleService} (create / publish / re-plan / structural-edit gates),</li>
 *   <li>the manual-scheduler rejection ({@code error.payment.tranche.invalid}) thrown by
 *       {@code ManualSchedulerService}, and</li>
 *   <li>the webhook signature rejection ({@code error.payment.webhook.unverified}) thrown by
 *       {@code PaymentWebhookController}.</li>
 * </ul>
 *
 * <p>Mirrors the bundle-loading approach of {@code ProcurementMessagesParityTest}.
 *
 * <p>Validates: Requirements 13.1, 13.2, 13.4
 */
class PaymentMessagesParityTest {

    /** Polish base bundle. */
    private static final String PL_BUNDLE = "messages.properties";
    /** Russian bundle. */
    private static final String RU_BUNDLE = "messages_ru.properties";

    private static final String[] FOR0607_KEYS = {
            "error.payment.generator.invalid",
            "error.payment.generator.percentSum",
            "error.payment.generator.amountSum",
            "error.payment.schedule.noProjectTotal",
            "error.payment.schedule.exists",
            "error.payment.schedule.locked",
            "error.payment.schedule.sumMismatch",
            "error.payment.schedule.cannotUnpublish",
            "error.payment.tranche.invalid",
            "error.payment.webhook.unverified"
    };

    @ParameterizedTest
    @ValueSource(strings = {
            "error.payment.generator.invalid",
            "error.payment.generator.percentSum",
            "error.payment.generator.amountSum",
            "error.payment.schedule.noProjectTotal",
            "error.payment.schedule.exists",
            "error.payment.schedule.locked",
            "error.payment.schedule.sumMismatch",
            "error.payment.schedule.cannotUnpublish",
            "error.payment.tranche.invalid",
            "error.payment.webhook.unverified"
    })
    @DisplayName("every FOR-06-07 message key has a non-blank PL entry that is not the raw key")
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
            "error.payment.generator.invalid",
            "error.payment.generator.percentSum",
            "error.payment.generator.amountSum",
            "error.payment.schedule.noProjectTotal",
            "error.payment.schedule.exists",
            "error.payment.schedule.locked",
            "error.payment.schedule.sumMismatch",
            "error.payment.schedule.cannotUnpublish",
            "error.payment.tranche.invalid",
            "error.payment.webhook.unverified"
    })
    @DisplayName("every FOR-06-07 message key has a non-blank RU entry that is not the raw key")
    void keyHasNonBlankRuEntry(String code) throws IOException {
        Properties ru = loadBundle(RU_BUNDLE);

        assertThat(ru.getProperty(code))
                .as("RU bundle '%s' must contain a non-blank entry for '%s'", RU_BUNDLE, code)
                .isNotNull()
                .isNotBlank()
                .isNotEqualTo(code);
    }

    @Test
    @DisplayName("all FOR-06-07 message keys are present in both bundles at once (identical key set)")
    void allKeysPresentInBothBundles() throws IOException {
        Properties pl = loadBundle(PL_BUNDLE);
        Properties ru = loadBundle(RU_BUNDLE);

        for (String code : FOR0607_KEYS) {
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
        try (InputStream in = PaymentMessagesParityTest.class
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
