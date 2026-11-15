package com.foremen.config.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts PL/RU parity of the FOR-11-05 (goods receipt) {@code error.*} message keys.
 *
 * <p>Validates: Requirements 13.2, 15.2
 */
class GoodsReceiptMessagesParityTest {

    private static final String PL_BUNDLE = "messages.properties";
    private static final String RU_BUNDLE = "messages_ru.properties";

    private static final String[] KEYS = {
            "error.goodsReceipt.notReceivable",
            "error.goodsReceipt.exceedsInFlight",
            "error.goodsReceipt.returnExceedsAccepted",
            "error.goodsReceipt.comment.required",
            "error.goodsReceipt.quantity.invalid",
            "error.goodsReceipt.fromAccepted.invalid",
            "error.goodsReceipt.shipment.mismatch",
            "error.goodsReceipt.alreadyVoided",
            "error.goodsReceipt.void.forbidden",
            "error.goodsReceipt.media.forbidden",
            "error.goodsReceipt.media.type",
            "error.goodsReceipt.media.size",
            "error.goodsReceipt.media.count",
            "error.shipment.hasReceipts",
            "error.shipment.position.hasReceipts"
    };

    static Stream<String> keys() {
        return Stream.of(KEYS);
    }

    @ParameterizedTest
    @MethodSource("keys")
    @DisplayName("every FOR-11-05 key has non-blank PL and RU entries that are not the raw key")
    void keyPresentInBothBundles(String code) throws IOException {
        for (String bundle : new String[] {PL_BUNDLE, RU_BUNDLE}) {
            assertThat(loadBundle(bundle).getProperty(code))
                    .as("'%s' must contain a non-blank entry for '%s'", bundle, code)
                    .isNotNull()
                    .isNotBlank()
                    .isNotEqualTo(code);
        }
    }

    @Test
    @DisplayName("PL and RU entries differ for every FOR-11-05 key (no copy-pasted translation)")
    void plAndRuDiffer() throws IOException {
        Properties pl = loadBundle(PL_BUNDLE);
        Properties ru = loadBundle(RU_BUNDLE);
        for (String code : KEYS) {
            assertThat(pl.getProperty(code))
                    .as("PL and RU text must differ for '%s'", code)
                    .isNotEqualTo(ru.getProperty(code));
        }
    }

    private static Properties loadBundle(String bundleName) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = GoodsReceiptMessagesParityTest.class
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
