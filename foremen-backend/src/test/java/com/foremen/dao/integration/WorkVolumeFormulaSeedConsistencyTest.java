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

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.formula.FormulaAst;
import com.foremen.service.formula.FormulaParser;
import com.foremen.service.formula.FormulaValidator;

/**
 * Guards the FOR-05-04-UI work-volume formula seed
 * ({@code 092-seed-work-volume-formulas.xml}) against parser/serializer drift.
 *
 * <p>The seed stores each work item's default volume formula as a {@code source_text} plus a
 * pre-parsed {@code parsed_ast} (JSONB, NOT NULL). Because the AST is hand-computed at seed
 * time, it MUST stay byte-for-byte consistent with what {@link FormulaParser} produces from the
 * same {@code source_text} — otherwise the persisted AST would diverge from the grammar and the
 * app would evaluate a stale/incorrect tree. This test parses each seeded {@code source_text}
 * with the real parser, serializes the resulting {@link FormulaAst} with the same Jackson
 * mapper the {@code jsonb} column uses, and asserts semantic JSON equality with the seeded
 * {@code parsed_ast}. It also runs {@link FormulaValidator} (with no known work refs, since the
 * seeded subset references only room dimensions) so a seeded formula that fails semantic
 * validation is caught here rather than at runtime.
 *
 * <p>Pure/offline: reads the changeset file from the module's {@code database_files} directory;
 * no database or Spring context is needed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkVolumeFormulaSeedConsistencyTest {

    private static final Path CHANGESET =
            Path.of("database_files/changesets/092-seed-work-volume-formulas.xml");

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** One seeded (source_text, parsed_ast) row extracted from the changeset SQL. */
    private record SeededFormula(String code, String sourceText, String parsedAstJson) {
    }

    /**
     * Extracts the seeded formulas from the changeset by matching each INSERT's
     * {@code SELECT wi.id, '<source>', '<ast>'::jsonb ... WHERE wi.code = '<code>'}.
     */
    private List<SeededFormula> readSeededFormulas() throws IOException {
        String xml = Files.readString(CHANGESET, StandardCharsets.UTF_8);
        // SELECT wi.id, '<source>', '<astJson>'::jsonb, NOW(), 'system'
        // SQL-string body matcher: runs of non-quotes separated by escaped '' pairs
        // (linear, no catastrophic backtracking).
        String sqlStr = "'([^']*(?:''[^']*)*)'";
        Pattern select = Pattern.compile(
                "SELECT\\s+wi\\.id,\\s*" + sqlStr + ",\\s*" + sqlStr + "::jsonb",
                Pattern.DOTALL);
        Pattern code = Pattern.compile("wi\\.code\\s*=\\s*'([^']+)'");
        Matcher sm = select.matcher(xml);
        Matcher cm = code.matcher(xml);
        List<SeededFormula> out = new ArrayList<>();
        while (sm.find() && cm.find()) {
            String source = sm.group(1).replace("''", "'");
            String ast = sm.group(2).replace("''", "'");
            out.add(new SeededFormula(cm.group(1), source, ast));
        }
        return out;
    }

    @Test
    @DisplayName("the seed changeset exists and contains formula inserts")
    void seedIsPresentAndNonEmpty() throws IOException {
        assertThat(Files.exists(CHANGESET)).as("092 changeset must exist").isTrue();
        assertThat(readSeededFormulas())
                .as("092 must seed at least one work-volume formula")
                .isNotEmpty();
    }

    @Test
    @DisplayName("every seeded parsed_ast equals FormulaParser.parse(source_text) and passes validation")
    void seededAstMatchesParserAndValidates() throws IOException {
        List<SeededFormula> seeded = readSeededFormulas();

        for (SeededFormula f : seeded) {
            // 1) The source text parses under the real grammar.
            FormulaAst parsed = FormulaParser.parse(f.sourceText());

            // 2) It passes semantic validation (only room dimensions, no work refs).
            FormulaValidator.validate(parsed, Set.of());

            // 3) The parser's serialized output equals the seeded parsed_ast. Compare the
            //    SERIALIZED TEXT (what actually lands in the jsonb column), not JsonNode trees:
            //    the parser models numeric literals as BigDecimal, so a tree comparison would
            //    treat DecimalNode(2) != IntNode(2) even though both serialize to "2" and are
            //    stored identically. Re-serializing the seed JSON through the same mapper
            //    normalizes formatting/key insertion so the comparison is on canonical text.
            String fromParser = objectMapper.writeValueAsString(parsed);
            String fromSeedCanonical =
                    objectMapper.writeValueAsString(objectMapper.readTree(f.parsedAstJson()));
            assertThat(fromParser)
                    .as("parsed_ast for code %s (source: %s) must match the parser output",
                            f.code(), f.sourceText())
                    .isEqualTo(fromSeedCanonical);
        }
    }
}
