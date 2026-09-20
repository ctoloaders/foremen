package com.foremen.util;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 4 (FOR-04-18): Pakiet cell normalization yields the correct package-token set.
 *
 * <p>For any comma-separated {@code Pakiet} cell built from the token alphabet
 * {@code {Budget, Standard, Lux, Standart}} with arbitrary surrounding whitespace, stray
 * double-quote characters, a leading {@code "+ "} marker, and repetition,
 * {@link PakietParser#parse(String)} yields exactly the set of normalized package tokens in
 * which each token is trimmed of whitespace and quotes, the leading {@code "+ "} marker is
 * removed, {@code Standart} is mapped to {@code Standard}, and duplicates are collapsed — so the
 * result contains {@code Standard} whenever the input contained {@code Standard} or
 * {@code Standart}, contains no empty token, and is order- and duplicate-independent.
 *
 * <p>Feature: FOR-04-18-finishing-materials, Property 4
 *
 * <p><b>Validates: Requirements 6.4</b>
 */
@Tag("Feature: FOR-04-18-finishing-materials, Property 4")
class PakietParserPropertyTest {

    private static final List<String> ALPHABET = List.of("Budget", "Standard", "Lux", "Standart");

    @Property(tries = 200)
    void parsedSetEqualsNormalizedExpectedSet(@ForAll("pakietCells") PakietCell cell) {
        Set<String> actual = PakietParser.parse(cell.raw());

        assertThat(actual).isEqualTo(cell.expected());
        // No empty token ever survives.
        assertThat(actual).doesNotContain("");
        // Only canonical tokens (Standart mapped away).
        assertThat(actual).doesNotContain("Standart");
        assertThat(actual).isSubsetOf("Budget", "Standard", "Lux");
    }

    @Property(tries = 200)
    void orderAndDuplicateIndependent(@ForAll("pakietCells") PakietCell cell) {
        // Reversing the token order and appending a duplicate of the first token must not change
        // the parsed set: the result is order- and duplicate-independent.
        List<String> reversedWithDup = new ArrayList<>(cell.tokens());
        java.util.Collections.reverse(reversedWithDup);
        if (!reversedWithDup.isEmpty()) {
            reversedWithDup.add(reversedWithDup.get(0));
        }
        String reversedRaw = String.join(",", reversedWithDup);

        assertThat(PakietParser.parse(reversedRaw)).isEqualTo(PakietParser.parse(cell.raw()));
    }

    @Property(tries = 100)
    void standartIsMappedToStandard(@ForAll("pakietCells") PakietCell cell) {
        boolean hadStandardOrStandart = cell.cleanTokens().stream()
                .anyMatch(t -> t.equalsIgnoreCase("Standard") || t.equalsIgnoreCase("Standart"));
        if (hadStandardOrStandart) {
            assertThat(PakietParser.parse(cell.raw())).contains("Standard");
        }
    }

    // --- Providers ---

    /**
     * Generates a {@link PakietCell}: a raw cell string decorated with random whitespace, quotes,
     * a leading {@code "+ "} marker and repetition, paired with the expected normalized set.
     */
    @Provide
    Arbitrary<PakietCell> pakietCells() {
        Arbitrary<List<String>> cleanTokenLists =
                Arbitraries.of(ALPHABET).list().ofMinSize(0).ofMaxSize(6);

        return cleanTokenLists.flatMap(cleanTokens -> {
            // For each clean token, build an arbitrary of its decorated (dirty) rendering.
            List<Arbitrary<String>> decoratedArbs = new ArrayList<>();
            for (String token : cleanTokens) {
                decoratedArbs.add(decorate(token));
            }
            Arbitrary<List<String>> decoratedListArb = flattenArbitraries(decoratedArbs);

            return decoratedListArb.map(decorated -> {
                String raw = String.join(",", decorated);
                Set<String> expected = new LinkedHashSet<>();
                for (String token : cleanTokens) {
                    expected.add(canonical(token));
                }
                return new PakietCell(raw, decorated, cleanTokens, expected);
            });
        });
    }

    /** Decorates a clean token with random surrounding whitespace, quotes and a leading "+ ". */
    private Arbitrary<String> decorate(String token) {
        Arbitrary<String> leadingWs = whitespace();
        Arbitrary<String> trailingWs = whitespace();
        Arbitrary<Integer> leadQuotes = Arbitraries.integers().between(0, 2);
        Arbitrary<Integer> trailQuotes = Arbitraries.integers().between(0, 2);
        Arbitrary<Integer> plusMarker = Arbitraries.integers().between(0, 2); // 0 none, 1 "+ ", 2 "+"

        return Combinators.combine(leadingWs, trailingWs, leadQuotes, trailQuotes, plusMarker)
                .as((lws, tws, lq, tq, plus) -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append(lws);
                    if (plus == 1) {
                        sb.append("+ ");
                    } else if (plus == 2) {
                        sb.append("+");
                    }
                    sb.append("\"".repeat(lq));
                    sb.append(token);
                    sb.append("\"".repeat(tq));
                    sb.append(tws);
                    return sb.toString();
                });
    }

    private Arbitrary<String> whitespace() {
        return Arbitraries.of(" ", "", "  ", "\t");
    }

    /** Combines a list of String arbitraries into an arbitrary of the joined list. */
    private Arbitrary<List<String>> flattenArbitraries(List<Arbitrary<String>> arbs) {
        Arbitrary<List<String>> acc = Arbitraries.just(new ArrayList<>());
        for (Arbitrary<String> arb : arbs) {
            acc = Combinators.combine(acc, arb).as((list, next) -> {
                List<String> copy = new ArrayList<>(list);
                copy.add(next);
                return copy;
            });
        }
        return acc;
    }

    private static String canonical(String token) {
        return token.equalsIgnoreCase("Standart") ? "Standard" : token;
    }

    /**
     * A generated Pakiet cell together with its expected normalization.
     *
     * @param raw         the decorated raw cell string fed to the parser
     * @param decorated   the per-token decorated renderings (joined by comma to form {@code raw})
     * @param cleanTokens the underlying clean tokens (pre-decoration, from the alphabet)
     * @param expected    the expected normalized token set
     */
    private record PakietCell(String raw, List<String> decorated, List<String> cleanTokens,
                              Set<String> expected) {
        List<String> tokens() {
            return decorated;
        }
    }
}
