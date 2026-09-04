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
 * Feature: FOR-03-05-otp-client-auth, Property 16: OTP message codes are localized in PL and RU
 *
 * For all OTP message codes in the required set (the four error codes
 * error.auth.otp.invalid, error.auth.otp.expired, error.auth.otp.attempts.exceeded,
 * error.auth.otp.rate.limited, plus the OTP email subject and body codes), both the
 * PL (messages.properties) and RU (messages_ru.properties) resources SHALL contain a
 * non-blank entry.
 *
 * Validates: Requirements 9.1, 9.2
 */
@Tag("Feature: FOR-03-05-otp-client-auth, Property 16: OTP message codes are localized in PL and RU")
class OtpMessagesPropertyTest {

    private final Properties plMessages = load("/messages.properties");
    private final Properties ruMessages = load("/messages_ru.properties");

    @Property(tries = 100)
    void everyRequiredOtpCodeIsLocalizedInPlAndRu(@ForAll("requiredCodes") String code) {
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
                // OTP error codes (Requirement 9.1)
                "error.auth.otp.invalid",
                "error.auth.otp.expired",
                "error.auth.otp.attempts.exceeded",
                "error.auth.otp.rate.limited",
                // OTP email subject/body codes (Requirement 9.2)
                "mail.otp.subject",
                "mail.otp.body"
        );
    }

    private static Properties load(String classpathResource) {
        try (InputStream in = OtpMessagesPropertyTest.class.getResourceAsStream(classpathResource)) {
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
