package com.foremen.dao.integration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.foremen.dao.model.formula.FormulaAst;
import com.foremen.service.formula.FormulaParser;
import com.foremen.service.formula.FormulaValidator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the FOR-05-04-UI work-package override seed
 * ({@code 093-seed-work-package-overrides.xml}) against parser/serializer drift.
 *
 * <p>The seed derives {@code (work item, offer package)} memberships and optional override
 * formulas from the {@code Oferta} AL/AM/AN package columns. Only the scalar-override rows carry
 * an {@code override_source_text}/{@code override_parsed_ast}; the flag-only membership rows carry
 * neither. For every override row, the pre-parsed {@code override_parsed_ast} (JSONB, hand-built
 * at seed time) MUST stay byte-for-byte consistent with what {@link FormulaParser} produces from
 * the same {@code override_source_text} — otherwise the persisted AST would diverge from the
 * grammar. This test parses each seeded override source with the real parser, serializes the AST
 * with the same Jackson mapper the {@code jsonb} column uses, and asserts equality (as canonical
 * serialized text — the parser models numeric literals as {@code BigDecimal}, so a JsonNode-tree
 * comparison would spuriously distinguish {@code DecimalNode(4)} from {@code IntNode(4)}). It also
 * runs {@link FormulaValidator} (no known work refs — the seeded overrides are pure numeric
 * constants) so a seeded override that fails semantic validation is caught here.
 *
 * <p>Pure/offline: reads the changeset file from the module's {@code database_files} directory; no
 * database or Spring context is needed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkPackageOverrideSeedConsistencyTest {

    private static final Path CHANGESET =
            Path.of("database_files/changesets/093-seed-work-package-overrides.xml");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** One seeded override row (only the scalar-override inserts, which carry source + ast). */
    private record SeededOverride(String code, String pkg, String sourceText, String parsedAstJson) {
    }

    /**
     * Extracts the scalar-override rows from the changeset. Matches only the INSERT variant that
     * carries {@code override_source_text}/{@code override_parsed_ast}, i.e. its SELECT is
     * {@code SELECT wi.id, op.id, TRUE, '<source>', '<astJson>'::jsonb, ...}, paired with the
     * {@code wi.code = '<code>' AND op.code = '<pkg>'} that follows it.
     */
    private List<SeededOverride> readSeededOverrides() throws IOException {
        String xml = Files.readString(CHANGESET, StandardCharsets.UTF_8);
        // Linear SQL-string body matcher (runs of non-quotes separated by escaped '' pairs).
        String sqlStr = "'([^']*(?:''[^']*)*)'";
        Pattern select = Pattern.compile(
                "SELECT\\s+wi\\.id,\\s*op\\.id,\\s*TRUE,\\s*" + sqlStr + ",\\s*" + sqlStr + "::jsonb"
                        + ".*?wi\\.code\\s*=\\s*'([^']+)'\\s*AND\\s*op\\.code\\s*=\\s*'([^']+)'",
                Pattern.DOTALL);
        Matcher m = select.matcher(xml);
        List<SeededOverride> out = new ArrayList<>();
        while (m.find()) {
            String source = m.group(1).replace("''", "'");
            String ast = m.group(2).replace("''", "'");
            out.add(new SeededOverride(m.group(3), m.group(4), source, ast));
        }
        return out;
    }

    @Test
    @DisplayName("the seed changeset exists and contains scalar-override rows")
    void seedIsPresentAndHasOverrides() throws IOException {
        assertThat(Files.exists(CHANGESET)).as("093 changeset must exist").isTrue();
        assertThat(readSeededOverrides())
                .as("093 must seed at least one scalar override with source + parsed AST")
                .isNotEmpty();
    }

    @Test
    @DisplayName("every seeded override_parsed_ast equals FormulaParser.parse(override_source_text) and validates")
    void seededOverrideAstMatchesParserAndValidates() throws IOException {
        List<SeededOverride> seeded = readSeededOverrides();

        for (SeededOverride o : seeded) {
            // 1) The override source parses under the real grammar.
            FormulaAst parsed = FormulaParser.parse(o.sourceText());

            // 2) It passes semantic validation (a pure numeric constant references nothing).
            FormulaValidator.validate(parsed, Set.of());

            // 3) The parser's serialized output equals the seeded parsed_ast (canonical text).
            String fromParser = objectMapper.writeValueAsString(parsed);
            String fromSeedCanonical =
                    objectMapper.writeValueAsString(objectMapper.readTree(o.parsedAstJson()));
            assertThat(fromParser)
                    .as("override_parsed_ast for %s / %s (source: %s) must match the parser output",
                            o.code(), o.pkg(), o.sourceText())
                    .isEqualTo(fromSeedCanonical);
        }
    }
}
