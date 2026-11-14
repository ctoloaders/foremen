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
 * Asserts PL/RU parity of the FOR-11-00 (procurement foundation) {@code error.*} message keys.
 *
 * <p>Validates: Requirements 16.1, 16.2
 */
class ProcurementFoundationMessagesParityTest {

    private static final String PL_BUNDLE = "messages.properties";
    private static final String RU_BUNDLE = "messages_ru.properties";

    private static final String[] KEYS = {
            "error.purchasePosition.notFound",
            "error.purchasePosition.kindNotSupported",
            "error.purchasePosition.project.notActive",
            "error.purchasePosition.quantity.invalid",
            "error.purchasePosition.price.invalid",
            "error.purchasePosition.material.projectMismatch",
            "error.purchasePosition.field.readOnly",
            "error.purchasePosition.field.forbidden",
            "error.purchasePosition.transition.notAllowed",
            "error.purchasePosition.transition.gateViolation",
            "error.purchasePosition.delete.notAllowed",
            "error.purchaseRequest.notFound",
            "error.purchaseRequest.buyer.invalid",
            "error.purchaseRequest.position.projectMismatch",
            "error.purchaseRequest.position.alreadyAssigned",
            "error.purchaseRequest.position.detachNotAllowed",
            "error.purchaseRequest.delete.notEmpty",
            "error.projectMaterial.notFound",
            "error.projectMaterial.project.notActive",
            "error.projectMaterial.project.immutable",
            "error.projectMaterial.inUse",
            "error.materialSeller.nip.invalid",
            "error.materialSeller.nip.duplicate",
            "error.nipLookup.unavailable",
            "error.materialSeller.quick.nameRequired",
            "error.purchasePosition.variant.notFound",
            "error.purchasePosition.variant.stageNotAllowed",
            "error.purchasePosition.variant.sameAsCurrent",
            "error.purchasePosition.upcoming.days.invalid"
    };

    @ParameterizedTest
    @ValueSource(strings = {
            "error.purchasePosition.notFound",
            "error.purchasePosition.kindNotSupported",
            "error.purchasePosition.project.notActive",
            "error.purchasePosition.quantity.invalid",
            "error.purchasePosition.price.invalid",
            "error.purchasePosition.material.projectMismatch",
            "error.purchasePosition.field.readOnly",
            "error.purchasePosition.field.forbidden",
            "error.purchasePosition.transition.notAllowed",
            "error.purchasePosition.transition.gateViolation",
            "error.purchasePosition.delete.notAllowed",
            "error.purchaseRequest.notFound",
            "error.purchaseRequest.buyer.invalid",
            "error.purchaseRequest.position.projectMismatch",
            "error.purchaseRequest.position.alreadyAssigned",
            "error.purchaseRequest.position.detachNotAllowed",
            "error.purchaseRequest.delete.notEmpty",
            "error.projectMaterial.notFound",
            "error.projectMaterial.project.notActive",
            "error.projectMaterial.project.immutable",
            "error.projectMaterial.inUse",
            "error.materialSeller.nip.invalid",
            "error.materialSeller.nip.duplicate",
            "error.nipLookup.unavailable",
            "error.materialSeller.quick.nameRequired",
            "error.purchasePosition.variant.notFound",
            "error.purchasePosition.variant.stageNotAllowed",
            "error.purchasePosition.variant.sameAsCurrent",
            "error.purchasePosition.upcoming.days.invalid"
    })
    @DisplayName("every FOR-11-00 key has non-blank PL and RU entries that are not the raw key")
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
    @DisplayName("PL and RU entries differ for every FOR-11-00 key (no copy-pasted translation)")
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
        try (InputStream in = ProcurementFoundationMessagesParityTest.class
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
