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
 * Feature: FOR-03-02-user-invitation, Property 20: Invitation message codes are localized in PL and RU
 *
 * For all invitation message codes in the required set (the five error codes plus the
 * subject and body codes of both email templates), both the PL (messages.properties) and
 * RU (messages_ru.properties) resources SHALL contain a non-blank entry.
 *
 * Validates: Requirements 8.1, 8.2
 */
@Tag("Feature: FOR-03-02-user-invitation, Property 20: Invitation message codes are localized in PL and RU")
class InviteMessagesPropertyTest {

    private final Properties plMessages = load("/messages.properties");
    private final Properties ruMessages = load("/messages_ru.properties");

    @Property(tries = 100)
    void everyRequiredInviteCodeIsLocalizedInPlAndRu(@ForAll("requiredCodes") String code) {
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
                // Invite error codes (Requirement 8.1)
                "error.invite.token.invalid",
                "error.invite.token.expired",
                "error.invite.token.used",
                "error.invite.user.not.found",
                "error.invite.user.already.active",
                // Invitation email subject/body codes (Requirement 8.2)
                "mail.invite.set-password.subject",
                "mail.invite.set-password.body",
                "mail.invite.client-portal.subject",
                "mail.invite.client-portal.body"
        );
    }

    private static Properties load(String classpathResource) {
        try (InputStream in = InviteMessagesPropertyTest.class.getResourceAsStream(classpathResource)) {
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
