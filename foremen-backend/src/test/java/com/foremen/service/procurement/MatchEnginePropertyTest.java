package com.foremen.service.procurement;

import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MatchTier;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for the pure {@link MatchEngine} (FOR-06-03-procurement-ocr, design §5.3).
 *
 * <p>The engine is a pure, deterministic, total function from {@code (ExtractedLine,
 * List<PlannedMaterial>)} to a {@link MatchResult} at the highest reachable tier. These properties
 * re-derive the expected outcome with independent oracles that mirror the <em>documented</em>
 * normalization / containment / tier rules (never the engine's own private helpers) and assert the
 * four cross-cutting invariants:
 *
 * <ul>
 *   <li><b>Property 1 — Match soundness:</b> {@code matchedMaterialId == null ⟺ tier == NONE}, the
 *       status pairs with the tier, and a non-{@code NONE} winner is always an on-candidate-set
 *       (on-estimate) material id. Validates Requirements 1.5, 4.1, 5.5.</li>
 *   <li><b>Property 2 — Highest-reachable tier:</b> the assigned tier equals the best tier any
 *       candidate reaches under the documented rules (and the empty / null set ⇒ {@code NONE}).
 *       Validates Requirements 4.2, 5.2, 5.3, 5.4, 5.5.</li>
 *   <li><b>Property 3 — Deterministic, order-independent winner + tie-break:</b> the result is
 *       invariant under candidate reordering and duplication, and repeated calls agree. Validates
 *       Requirements 5.1, 5.6.</li>
 *   <li><b>Property 4 — Normalization invariance:</b> applying case / whitespace / PL-diacritic
 *       transforms to every matched text leaves the result unchanged. Validates Requirement 5.7.</li>
 * </ul>
 *
 * <p>The generators vary case, internal/edge whitespace, Polish diacritics (ś/ż/ł/ą…), and Cyrillic
 * tokens across the manufacturer, category, and name dimensions, and exercise manufacturer /
 * category / name overlaps, duplicate candidates, blank manufacturer hints, and the empty candidate
 * set.
 *
 * <p>No Spring context, no database — the engine is pure and exercised over in-memory values.
 *
 * <p>Feature: FOR-06-03-procurement-ocr, Properties 1–4
 */
@Tag("Feature: FOR-06-03-procurement-ocr, Property 1")
class MatchEnginePropertyTest {

    private final MatchEngine engine = new MatchEngine();

    // =============================================================================================
    // Property 1: Match soundness — matched id null ⟺ tier NONE, status pairs with tier, and a
    // non-NONE winner is always a candidate on the supplied (on-estimate) set.
    // Validates: Requirements 1.5, 4.1, 5.5
    // =============================================================================================

    @Property(tries = 300)
    @Tag("Feature: FOR-06-03-procurement-ocr, Property 1")
    void matchSoundness(@ForAll("scenarios") Scenario scenario) {
        MatchResult result = engine.match(scenario.line(), scenario.candidates());

        // The R1.5 biconditional and the status pairing.
        assertThat(engine.satisfiesInvariant(result))
                .as("matchedMaterialId == null ⟺ tier == NONE, with paired status")
                .isTrue();

        if (result.tier() == MatchTier.NONE) {
            assertThat(result.matchedMaterialId()).isNull();
            assertThat(result.status()).isEqualTo(MatchStatus.UNMATCHED);
        } else {
            assertThat(result.matchedMaterialId()).isNotNull();
            assertThat(result.status()).isEqualTo(MatchStatus.AUTO);
            // Soundness (R4.1): the winner is one of the supplied on-estimate candidates.
            Set<Long> candidateIds = scenario.candidates().stream()
                    .filter(c -> c != null && c.materialId() != null)
                    .map(PlannedMaterial::materialId)
                    .collect(Collectors.toSet());
            assertThat(candidateIds).contains(result.matchedMaterialId());
        }
    }

    @Property(tries = 100)
    @Tag("Feature: FOR-06-03-procurement-ocr, Property 1")
    void emptyOrNullCandidateSetIsAlwaysUnmatched(@ForAll("lines") ExtractedLine line) {
        MatchResult onEmpty = engine.match(line, Collections.emptyList());
        MatchResult onNull = engine.match(line, null);

        for (MatchResult result : List.of(onEmpty, onNull)) {
            assertThat(result.tier()).isEqualTo(MatchTier.NONE);
            assertThat(result.matchedMaterialId()).isNull();
            assertThat(result.status()).isEqualTo(MatchStatus.UNMATCHED);
            assertThat(engine.satisfiesInvariant(result)).isTrue();
        }
    }

    // =============================================================================================
    // Property 2: Highest-reachable tier assignment — the engine's tier equals the best tier any
    // candidate reaches under the documented rules; empty set ⇒ NONE.
    // Validates: Requirements 4.2, 5.2, 5.3, 5.4, 5.5
    // =============================================================================================

    @Property(tries = 300)
    @Tag("Feature: FOR-06-03-procurement-ocr, Property 2")
    void highestReachableTierMatchesOracle(@ForAll("scenarios") Scenario scenario) {
        MatchResult result = engine.match(scenario.line(), scenario.candidates());

        MatchTier expectedTier = oracleTier(scenario.line(), scenario.candidates());
        assertThat(result.tier())
                .as("engine must assign the highest reachable tier")
                .isEqualTo(expectedTier);
    }

    @Property(tries = 100)
    @Tag("Feature: FOR-06-03-procurement-ocr, Property 2")
    void emptyCandidateSetYieldsNoneTier(@ForAll("lines") ExtractedLine line) {
        assertThat(engine.match(line, new ArrayList<>()).tier()).isEqualTo(MatchTier.NONE);
    }

    // =============================================================================================
    // Property 3: Deterministic, order-independent winner + tie-break — the result is invariant
    // under candidate reordering and duplication, and repeated calls agree.
    // Validates: Requirements 5.1, 5.6
    // =============================================================================================

    @Property(tries = 300)
    @Tag("Feature: FOR-06-03-procurement-ocr, Property 3")
    void winnerIsDeterministicAndOrderIndependent(
            @ForAll("scenarios") Scenario scenario,
            @ForAll("seeds") long seed) {
        MatchResult reference = engine.match(scenario.line(), scenario.candidates());

        // Repeated call on the same input: determinism.
        MatchResult repeated = engine.match(scenario.line(), scenario.candidates());
        assertThat(repeated).isEqualTo(reference);

        // Reordered candidate list: order independence (same winner + tier + status).
        List<PlannedMaterial> shuffled = new ArrayList<>(scenario.candidates());
        Collections.shuffle(shuffled, new Random(seed));
        MatchResult onShuffled = engine.match(scenario.line(), shuffled);
        assertThat(onShuffled)
                .as("winner must not depend on candidate order")
                .isEqualTo(reference);
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-06-03-procurement-ocr, Property 3")
    void duplicatingCandidatesDoesNotChangeTheWinner(@ForAll("scenarios") Scenario scenario) {
        MatchResult reference = engine.match(scenario.line(), scenario.candidates());

        // Duplicate every candidate; the deterministic winner (and its id) must be unchanged.
        List<PlannedMaterial> doubled = new ArrayList<>(scenario.candidates());
        doubled.addAll(scenario.candidates());
        MatchResult onDoubled = engine.match(scenario.line(), doubled);

        assertThat(onDoubled.tier()).isEqualTo(reference.tier());
        assertThat(onDoubled.matchedMaterialId()).isEqualTo(reference.matchedMaterialId());
        assertThat(onDoubled.status()).isEqualTo(reference.status());
    }

    // =============================================================================================
    // Property 4: Normalization invariance — case / whitespace / PL-diacritic transforms applied to
    // every matched text leave the result unchanged.
    // Validates: Requirement 5.7
    // =============================================================================================

    @Property(tries = 300)
    @Tag("Feature: FOR-06-03-procurement-ocr, Property 4")
    void resultIsInvariantUnderCaseWhitespaceAndDiacriticTransforms(
            @ForAll("scenarios") Scenario scenario,
            @ForAll("seeds") long seed) {
        MatchResult reference = engine.match(scenario.line(), scenario.candidates());

        Random random = new Random(seed);
        ExtractedLine perturbedLine = new ExtractedLine(
                perturb(scenario.line().rawName(), random),
                perturb(scenario.line().manufacturerHint(), random));
        List<PlannedMaterial> perturbedCandidates = scenario.candidates().stream()
                .map(c -> c == null ? null : new PlannedMaterial(
                        c.materialId(),
                        perturb(c.manufacturer(), random),
                        perturb(c.category(), random),
                        c.names() == null ? null : c.names().stream()
                                .map(n -> perturb(n, random))
                                .collect(Collectors.toList())))
                .collect(Collectors.toList());

        MatchResult perturbed = engine.match(perturbedLine, perturbedCandidates);

        assertThat(perturbed)
                .as("case/whitespace/diacritic variants must not change the match")
                .isEqualTo(reference);
    }

    @Property(tries = 100)
    @Tag("Feature: FOR-06-03-procurement-ocr, Property 4")
    void normalizeFoldsCaseWhitespaceAndDiacritics(@ForAll("tokens") String token) {
        String base = MatchEngine.normalize(token);
        String withCaseAndSpace = MatchEngine.normalize("  " + token.toUpperCase() + "   ");
        assertThat(withCaseAndSpace).isEqualTo(base);

        // Śnieżka / sniezka fold equal; spacing and case never matter.
        assertThat(MatchEngine.normalize("Śnieżka")).isEqualTo(MatchEngine.normalize("  sniezka "));
        assertThat(MatchEngine.normalize("Łódź")).isEqualTo(MatchEngine.normalize("lodz"));
        assertThat(MatchEngine.normalize(null)).isEmpty();
    }

    // =============================================================================================
    // Independent oracle (mirrors the documented rules, not the engine's private helpers)
    // =============================================================================================

    /** The highest tier any candidate reaches, recomputed from the documented rules (design §5.3). */
    private static MatchTier oracleTier(ExtractedLine line, List<PlannedMaterial> candidates) {
        if (line == null || candidates == null || candidates.isEmpty()) {
            return MatchTier.NONE;
        }
        String normRaw = normalize(line.rawName());
        String normHint = normalize(line.manufacturerHint());

        boolean anyExact = false;
        boolean anyManuCat = false;
        boolean anyCat = false;

        for (PlannedMaterial c : candidates) {
            if (c == null) {
                continue;
            }
            boolean manu = manufacturerMatch(normHint, c);
            boolean cat = categoryMatch(normRaw, c);
            boolean name = nameMatch(normRaw, c);
            if (manu && cat && name) {
                anyExact = true;
            } else if (manu && cat) {
                anyManuCat = true;
            } else if (cat) {
                anyCat = true;
            }
        }
        if (anyExact) {
            return MatchTier.EXACT;
        }
        if (anyManuCat) {
            return MatchTier.MANUFACTURER_CATEGORY;
        }
        if (anyCat) {
            return MatchTier.CATEGORY;
        }
        return MatchTier.NONE;
    }

    private static boolean manufacturerMatch(String normHint, PlannedMaterial c) {
        if (normHint.isEmpty()) {
            return false;
        }
        String m = normalize(c.manufacturer());
        return !m.isEmpty() && normHint.equals(m);
    }

    private static boolean categoryMatch(String normRaw, PlannedMaterial c) {
        if (normRaw.isEmpty()) {
            return false;
        }
        String cat = normalize(c.category());
        return !cat.isEmpty() && normRaw.contains(cat);
    }

    private static boolean nameMatch(String normRaw, PlannedMaterial c) {
        if (normRaw.isEmpty() || c.names() == null) {
            return false;
        }
        for (String n : c.names()) {
            String nn = normalize(n);
            if (!nn.isEmpty() && normRaw.contains(nn)) {
                return true;
            }
        }
        return false;
    }

    /** A standalone re-implementation of the documented normalization, independent of the engine. */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String mapped = value.replace('ł', 'l').replace('Ł', 'L');
        String decomposed = java.text.Normalizer.normalize(mapped, java.text.Normalizer.Form.NFD);
        String stripped = decomposed.replaceAll("\\p{M}+", "");
        String lower = stripped.toLowerCase(java.util.Locale.ROOT);
        return lower.replaceAll("\\s+", " ").trim();
    }

    // =============================================================================================
    // Perturbation: apply case / whitespace / diacritic-equivalent transforms that MUST normalize
    // to the same value (so the match result is invariant).
    // =============================================================================================

    private static String perturb(String value, Random random) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        // leading whitespace
        sb.append(randomSpaces(random));
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            // Randomize case (normalization lower-cases, so this is invariant).
            if (random.nextBoolean()) {
                ch = Character.toUpperCase(ch);
            } else {
                ch = Character.toLowerCase(ch);
            }
            // Re-insert a PL diacritic where the base char is its stripped form — normalization
            // strips it back, so Śnieżka ≡ sniezka holds in both directions.
            char out = maybeReDiacritic(ch, random);
            sb.append(out);
            // Expand a single internal space into a run of whitespace.
            if (ch == ' ' && random.nextBoolean()) {
                sb.append(randomSpaces(random));
            }
        }
        sb.append(randomSpaces(random));
        return sb.toString();
    }

    private static char maybeReDiacritic(char ch, Random random) {
        if (!random.nextBoolean()) {
            return ch;
        }
        switch (ch) {
            case 'a': return 'ą';
            case 'c': return 'ć';
            case 'e': return 'ę';
            case 'n': return 'ń';
            case 'o': return 'ó';
            case 's': return 'ś';
            case 'z': return random.nextBoolean() ? 'ż' : 'ź';
            case 'l': return 'ł';
            default: return ch;
        }
    }

    private static String randomSpaces(Random random) {
        int n = random.nextInt(3); // 0..2 extra spaces
        return " ".repeat(n);
    }

    // =============================================================================================
    // Generators
    // =============================================================================================

    /**
     * A scenario: one line plus a candidate set, built so matches of every tier are reachable. The
     * category/name/manufacturer tokens are drawn from a small vocabulary (so containment overlaps
     * actually occur) and each is dressed with case / whitespace / diacritic / Cyrillic variation.
     */
    record Scenario(ExtractedLine line, List<PlannedMaterial> candidates) {
    }

    private static final List<String> CATEGORY_VOCAB =
            List.of("farba", "klej", "płytka", "gips", "цемент", "грунт");
    private static final List<String> NAME_VOCAB =
            List.of("śnieżka", "atlas", "ceresit", "knauf", "старатели", "unakol");
    private static final List<String> MANUFACTURER_VOCAB =
            List.of("Śnieżka", "Atlas", "Ceresit", "Knauf", "СТАРАТЕЛИ", "Mapei");

    @Provide
    Arbitrary<String> tokens() {
        Arbitrary<String> fromVocab = Arbitraries.of(
                concatVocab(CATEGORY_VOCAB, NAME_VOCAB, MANUFACTURER_VOCAB));
        return fromVocab;
    }

    private static List<String> concatVocab(List<String>... lists) {
        List<String> all = new ArrayList<>();
        for (List<String> l : lists) {
            all.addAll(l);
        }
        return all;
    }

    @Provide
    Arbitrary<Long> seeds() {
        return Arbitraries.longs();
    }

    @Provide
    Arbitrary<ExtractedLine> lines() {
        Arbitrary<String> rawName = rawNameArbitrary();
        Arbitrary<String> hint = manufacturerHintArbitrary();
        return Combinators.combine(rawName, hint).as(ExtractedLine::new);
    }

    /**
     * A raw name = an optional category token + an optional name token + noise, so category and
     * name containment are reachable but not guaranteed.
     */
    private Arbitrary<String> rawNameArbitrary() {
        Arbitrary<String> category = Arbitraries.of(CATEGORY_VOCAB).map(MatchEnginePropertyTest::dress);
        Arbitrary<String> name = Arbitraries.of(NAME_VOCAB).map(MatchEnginePropertyTest::dress);
        Arbitrary<String> noise = Arbitraries.of("5kg", "10L", "", "op.", "szt", "123");
        return Combinators.combine(category, name, noise)
                .as((cat, nm, ns) -> (cat + " " + nm + " " + ns).trim());
    }

    private Arbitrary<String> manufacturerHintArbitrary() {
        // A blank hint is a first-class case (cannot reach a manufacturer tier).
        Arbitrary<String> present = Arbitraries.of(MANUFACTURER_VOCAB).map(MatchEnginePropertyTest::dress);
        Arbitrary<String> blank = Arbitraries.of("", "   ");
        return Arbitraries.oneOf(present, present, blank);
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<ExtractedLine> line = lines();
        Arbitrary<List<PlannedMaterial>> candidates =
                plannedMaterial().list().ofMinSize(0).ofMaxSize(8);
        return Combinators.combine(line, candidates).as(Scenario::new);
    }

    private Arbitrary<PlannedMaterial> plannedMaterial() {
        Arbitrary<Long> id = Arbitraries.longs().between(1, 500);
        Arbitrary<String> manufacturer = Arbitraries.of(MANUFACTURER_VOCAB).map(MatchEnginePropertyTest::dress);
        Arbitrary<String> category = Arbitraries.of(CATEGORY_VOCAB).map(MatchEnginePropertyTest::dress);
        Arbitrary<List<String>> names = Arbitraries.of(NAME_VOCAB)
                .map(MatchEnginePropertyTest::dress)
                .list().ofMinSize(1).ofMaxSize(2);
        return Combinators.combine(id, manufacturer, category, names).as(PlannedMaterial::new);
    }

    /** Dress a vocabulary token with random case + surrounding whitespace (normalization-stable). */
    private static String dress(String token) {
        // Deterministic-per-token light dressing; the perturbation property covers heavier cases.
        String cased = token.length() > 1
                ? Character.toUpperCase(token.charAt(0)) + token.substring(1)
                : token.toUpperCase();
        return " " + cased + " ";
    }
}
