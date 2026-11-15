package com.foremen.config.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity test for the FOR-11-04 {@code error.shipment.*} message keys: every key of Requirement 24.2
 * exists with a non-blank, non-raw-key value in both the PL and RU bundles, and neither bundle holds
 * an {@code error.shipment.*} key the other lacks.
 *
 * <p>Validates: Requirements 24.1, 24.2, 24.3
 */
class ShipmentMessagesParityTest {

    private static final String PL_BUNDLE = "messages.properties";
    private static final String RU_BUNDLE = "messages_ru.properties";
    private static final String PREFIX = "error.shipment.";

    private static final Set<String> REQUIRED_KEYS = Set.of(
            "error.shipment.notFound",
            "error.shipment.terminal",
            "error.shipment.carrier.unknown",
            "error.shipment.trackingNumber.invalid",
            "error.shipment.link.invalid",
            "error.shipment.phone.invalid",
            "error.shipment.method.fieldsMissing",
            "error.shipment.position.notShippable",
            "error.shipment.line.exceedsCap",
            "error.shipment.line.exceedsOrderLine",
            "error.shipment.line.duplicate",
            "error.shipment.line.empty",
            "error.shipment.project.mismatch",
            "error.shipment.refresh.notAvailable",
            "error.shipment.refresh.tooSoon",
            "error.shipment.refresh.carrierPaused",
            "error.shipment.eta.revertNotAvailable");

    @Test
    @DisplayName("every required error.shipment.* key has a real PL and RU message")
    void requiredKeysPresentInBothBundles() throws IOException {
        Properties pl = loadBundle(PL_BUNDLE);
        Properties ru = loadBundle(RU_BUNDLE);

        for (String key : REQUIRED_KEYS) {
            assertThat(pl.getProperty(key)).as("PL value for '%s'", key)
                    .isNotNull().isNotBlank().isNotEqualTo(key);
            assertThat(ru.getProperty(key)).as("RU value for '%s'", key)
                    .isNotNull().isNotBlank().isNotEqualTo(key);
        }
    }

    @Test
    @DisplayName("PL and RU bundles hold the identical set of error.shipment.* keys")
    void shipmentKeySetsAreIdentical() throws IOException {
        assertThat(shipmentKeys(loadBundle(PL_BUNDLE)))
                .isEqualTo(shipmentKeys(loadBundle(RU_BUNDLE)))
                .containsAll(REQUIRED_KEYS);
    }

    @Test
    @DisplayName("messages have no placeholders, so they cannot echo carrier bodies or other projects' data")
    void messagesDoNotInterpolateDetails() throws IOException {
        for (String bundle : new String[] {PL_BUNDLE, RU_BUNDLE}) {
            Properties p = loadBundle(bundle);
            for (String key : shipmentKeys(p)) {
                assertThat(p.getProperty(key)).as("%s / %s", bundle, key).doesNotContain("{");
            }
        }
    }

    private static Set<String> shipmentKeys(Properties p) {
        Set<String> keys = new TreeSet<>();
        for (String name : p.stringPropertyNames()) {
            if (name.startsWith(PREFIX)) {
                keys.add(name);
            }
        }
        return keys;
    }

    private static Properties loadBundle(String bundleName) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = ShipmentMessagesParityTest.class.getClassLoader()
                .getResourceAsStream(bundleName)) {
            assertThat(in).as("Bundle '%s' on classpath", bundleName).isNotNull();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
