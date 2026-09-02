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
 * Feature: FOR-03-04-project-ownership, Property 5: New error codes are localized in PL and RU
 *
 * <p>For each of the three new project-member error codes, both the PL base bundle
 * (messages.properties) and the RU bundle (messages_ru.properties) SHALL contain a
 * non-blank entry.</p>
 *
 * <p><b>Validates: Requirements 9.1, 9.2, 9.3</b></p>
 */
@Tag("Feature: FOR-03-04-project-ownership, Property 5: New error codes are localized in PL and RU")
class ProjectMemberMessagesPropertyTest {

    private final Properties plMessages = load("/messages.properties");
    private final Properties ruMessages = load("/messages_ru.properties");

    // Feature: FOR-03-04-project-ownership, Property 5: New error codes are localized in PL and RU
    @Property(tries = 100)
    void everyNewProjectMemberCodeIsLocalizedInPlAndRu(@ForAll("requiredCodes") String code) {
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
                "error.project.member.duplicate",
                "error.project.role.not.found",
                "error.project.member.not.found"
        );
    }

    private static Properties load(String classpathResource) {
        try (InputStream in = ProjectMemberMessagesPropertyTest.class.getResourceAsStream(classpathResource)) {
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
