package com.foremen.config.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PL/RU parity of the FOR-07-04 (company money ledger: bank + cash) error codes.
 *
 * <p>The authoritative code list is derived from the main sources at test time: every string literal
 * matching {@code "error.bank.*"}, {@code "error.procurement.cash.*"} or
 * {@code "error.procurement.payment.*"} under {@code src/main/java} must have a non-blank entry that
 * is not the raw key in BOTH the Polish base bundle ({@code messages.properties}) and the Russian
 * bundle ({@code messages_ru.properties}). New codes added later are therefore covered automatically.
 *
 * <p>Validates: Requirements 23.4, 12.3
 */
class BankLedgerMessagesParityTest {

    private static final String PL_BUNDLE = "messages.properties";
    private static final String RU_BUNDLE = "messages_ru.properties";
    private static final Path MAIN_SOURCES = Paths.get("src", "main", "java");
    private static final Pattern CODE =
            Pattern.compile("\"(error\\.(?:bank|procurement\\.cash|procurement\\.payment)[A-Za-z0-9_.]*)\"");

    /** Codes built outside plain throw sites (connector defaults / Salt Edge mapping) — must stay covered. */
    private static final Set<String> CONNECTOR_KEYS = Set.of(
            "error.bank.provider.config",
            "error.bank.provider.reauth",
            "error.bank.provider.limit",
            "error.bank.provider.unavailable",
            "error.bank.provider.transient",
            "error.bank.provider.connectionLimit",
            "error.bank.provider.keysRequired");

    @Test
    @DisplayName("every FOR-07-04 error code used in main sources resolves in the PL and RU bundles")
    void everyUsedCodeResolvesInBothBundles() throws IOException {
        Set<String> codes = usedCodes();
        assertThat(codes).as("main sources must reference FOR-07-04 error codes").containsAll(CONNECTOR_KEYS);

        Properties pl = loadBundle(PL_BUNDLE);
        Properties ru = loadBundle(RU_BUNDLE);
        List<String> missing = new ArrayList<>();
        for (String code : codes) {
            if (!resolves(pl, code)) {
                missing.add(PL_BUNDLE + ": " + code);
            }
            if (!resolves(ru, code)) {
                missing.add(RU_BUNDLE + ": " + code);
            }
        }
        assertThat(missing).as("missing / blank bundle entries").isEmpty();
    }

    @Test
    @DisplayName("error.bank.refresh.notYetPossible renders its {0} earliest-time parameter in PL and RU")
    void refreshNotYetPossibleRendersTimeParameter() throws IOException {
        String at = "2026-01-02T03:04:05Z";
        for (String bundle : List.of(PL_BUNDLE, RU_BUNDLE)) {
            String pattern = loadBundle(bundle).getProperty("error.bank.refresh.notYetPossible");
            assertThat(new MessageFormat(pattern, Locale.ROOT).format(new Object[] {at}))
                    .as("%s must render the earliest refresh time", bundle)
                    .contains(at);
        }
    }

    private static boolean resolves(Properties bundle, String code) {
        String value = bundle.getProperty(code);
        return value != null && !value.isBlank() && !value.equals(code);
    }

    private static Set<String> usedCodes() throws IOException {
        Set<String> codes = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                Matcher m = CODE.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    codes.add(m.group(1));
                }
            }
        }
        return codes;
    }

    private static Properties loadBundle(String bundleName) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = BankLedgerMessagesParityTest.class.getClassLoader().getResourceAsStream(bundleName)) {
            assertThat(in).as("Message bundle '%s' must be on the classpath", bundleName).isNotNull();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
