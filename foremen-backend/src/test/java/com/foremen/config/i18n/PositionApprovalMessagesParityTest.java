package com.foremen.config.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts PL/RU parity of the FOR-11-02 (position approval) {@code error.positionApproval.*} keys.
 * The key set is the union of the design's list and every string literal with the prefix found in
 * the production sources.
 *
 * <p>Validates: Requirements 16.1, 16.3
 */
class PositionApprovalMessagesParityTest {

    private static final String PREFIX = "error.positionApproval.";
    private static final Pattern LITERAL = Pattern.compile("\"(" + Pattern.quote(PREFIX) + "[A-Za-z0-9.]+)\"");

    private static final List<String> DESIGN_KEYS = List.of(
            "round.notFound", "round.notOpen", "stage.notAllowed", "stale", "priceChanged",
            "send.notEligible", "send.priceMissing", "choice.optionsInvalid", "option.invalid",
            "option.priceNotAllowed", "option.sameAsCurrent", "deadline.invalid",
            "decision.commentRequired", "suggestion.linkInvalid", "suggestion.notOpen",
            "clientOwn.notAllowed", "onBehalf.forbidden", "staff.forbidden",
            "material.projectMismatch", "project.notActive");

    @Test
    @DisplayName("every error.positionApproval.* key (design + code constants) is in PL and RU bundles")
    void allKeysPresentInBothBundles() throws IOException {
        Set<String> keys = new TreeSet<>();
        DESIGN_KEYS.forEach(k -> keys.add(PREFIX + k));
        keys.addAll(scanSources());

        Properties pl = loadBundle("messages.properties");
        Properties ru = loadBundle("messages_ru.properties");
        for (String key : keys) {
            for (Properties bundle : new Properties[] {pl, ru}) {
                assertThat(bundle.getProperty(key))
                        .as("bundle entry for '%s'", key)
                        .isNotNull()
                        .isNotBlank()
                        .isNotEqualTo(key);
            }
        }
    }

    private static Set<String> scanSources() throws IOException {
        Set<String> found = new TreeSet<>();
        Path root = Path.of("src", "main", "java");
        if (!Files.isDirectory(root)) {
            return found;
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                Matcher m = LITERAL.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    found.add(m.group(1));
                }
            }
        }
        return found;
    }

    private static Properties loadBundle(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = PositionApprovalMessagesParityTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            assertThat(in).as("bundle '%s' on classpath", name).isNotNull();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
