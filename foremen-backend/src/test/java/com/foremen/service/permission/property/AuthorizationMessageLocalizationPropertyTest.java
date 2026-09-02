package com.foremen.service.permission.property;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test for authorization-message localization.
 *
 * <p>Covers the design property assigned to task 7.1:</p>
 * <ul>
 *   <li><b>Property 10: Authorization message codes are localized in PL and RU</b>
 *       &mdash; Validates Requirements 10.1, 10.2</li>
 * </ul>
 *
 * <p>Both message codes ({@code error.access.denied} and {@code error.auth.unauthorized})
 * already exist in both bundles; this test guards against a regression that would remove or
 * blank them.</p>
 */
class AuthorizationMessageLocalizationPropertyTest {

    /** Polish base bundle. */
    private static final String PL_BUNDLE = "messages.properties";
    /** Russian bundle. */
    private static final String RU_BUNDLE = "messages_ru.properties";

    // Feature: FOR-03-03-permission-evaluator, Property 10: Authorization message codes are localized in PL and RU.
    // For any code in { error.access.denied, error.auth.unauthorized }, both the Polish base bundle
    // (messages.properties) and the Russian bundle (messages_ru.properties) contain a non-blank entry for it.
    /**
     * <b>Validates: Requirements 10.1, 10.2</b>
     */
    @Property(tries = 100)
    void authorizationCodesAreNonBlankInBothBundles(@ForAll("authorizationMessageCodes") String code)
            throws IOException {

        Properties pl = loadBundle(PL_BUNDLE);
        Properties ru = loadBundle(RU_BUNDLE);

        assertThat(pl.getProperty(code))
                .as("PL bundle '%s' must contain a non-blank entry for '%s'", PL_BUNDLE, code)
                .isNotNull()
                .isNotBlank();

        assertThat(ru.getProperty(code))
                .as("RU bundle '%s' must contain a non-blank entry for '%s'", RU_BUNDLE, code)
                .isNotNull()
                .isNotBlank();
    }

    /** The authorization message codes enforced by this feature. */
    @Provide
    Arbitrary<String> authorizationMessageCodes() {
        return Arbitraries.of("error.access.denied", "error.auth.unauthorized");
    }

    /** Loads a message bundle from the classpath as UTF-8 properties. */
    private static Properties loadBundle(String bundleName) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = AuthorizationMessageLocalizationPropertyTest.class
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
