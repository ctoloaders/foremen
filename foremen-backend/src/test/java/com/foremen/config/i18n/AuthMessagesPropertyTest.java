package com.foremen.config.i18n;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feature: FOR-03-01-jwt-auth, Property 31: All authentication message codes are localized in PL and RU
 *
 * For all authentication message codes in the required set, both the PL
 * (messages.properties) and RU (messages_ru.properties) resources SHALL contain a
 * non-blank entry.
 *
 * Validates: Requirements 15.1, 12.5
 */
@Tag("Feature: FOR-03-01-jwt-auth, Property 31: All authentication message codes are localized in PL and RU")
class AuthMessagesPropertyTest {

    private final Properties plMessages = load("/messages.properties");
    private final Properties ruMessages = load("/messages_ru.properties");

    @Property(tries = 100)
    void everyRequiredAuthCodeIsLocalizedInPlAndRu(@ForAll("requiredCodes") String code) {
        String plValue = plMessages.getProperty(code);
        assertNotNull(plValue, "PL resource (messages.properties) is missing code: " + code);
        assertFalse(plValue.isBlank(), "PL entry for code is blank: " + code);

        String ruValue = ruMessages.getProperty(code);
        assertNotNull(ruValue, "RU resource (messages_ru.properties) is missing code: " + code);
        assertFalse(ruValue.isBlank(), "RU entry for code is blank: " + code);
    }

    @Provide
    Arbitrary<String> requiredCodes() {
        return Arbitraries.of(
                "error.auth.invalid.credentials",
                "error.auth.account.not.activated",
                "error.auth.account.deactivated",
                "error.auth.refresh.invalid",
                "error.auth.refresh.revoked",
                "error.auth.refresh.expired",
                "error.auth.reset.token.invalid",
                "error.auth.unauthorized",
                "error.user.admin.role.forbidden"
        );
    }

    private static Properties load(String classpathResource) {
        try (InputStream in = AuthMessagesPropertyTest.class.getResourceAsStream(classpathResource)) {
            assertNotNull(in, "Classpath resource not found: " + classpathResource);
            Properties properties = new Properties();
            // .properties resources in this project are UTF-8 encoded (see ResourceBundleMessageSource
            // defaultEncoding=UTF-8), so read them with an explicit UTF-8 reader.
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            assertTrue(!properties.isEmpty(), "Resource loaded no entries: " + classpathResource);
            return properties;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + classpathResource, e);
        }
    }
}
