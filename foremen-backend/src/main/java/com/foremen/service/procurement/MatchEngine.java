package com.foremen.service.procurement;

import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MatchTier;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * FOR-06-03 (design §5.3, Requirements 4.1, 4.2, 5.1–5.8, 1.5) — the pure, deterministic per-line
 * tier match.
 *
 * <p>Given one {@link ExtractedLine} and the project's {@link PlannedMaterial} candidate set, the
 * engine assigns the <strong>highest reachable</strong> tier and (for a non-{@code NONE} tier) a
 * single deterministic winning candidate:
 *
 * <table>
 *   <caption>Tier rules (highest reachable)</caption>
 *   <tr><th>Tier</th><th>Condition (all comparisons over {@link #normalize(String) normalized} text)</th><th>%</th></tr>
 *   <tr><td>{@code EXACT}</td><td>manufacturer + category + name all match</td><td>100</td></tr>
 *   <tr><td>{@code MANUFACTURER_CATEGORY}</td><td>no {@code EXACT}, but manufacturer + category match</td><td>80</td></tr>
 *   <tr><td>{@code CATEGORY}</td><td>no higher tier, but category matches</td><td>50</td></tr>
 *   <tr><td>{@code NONE}</td><td>no candidate at any tier → {@code matchedMaterialId = null}, {@code UNMATCHED}</td><td>0</td></tr>
 * </table>
 *
 * <ul>
 *   <li><strong>Manufacturer match</strong> — {@code normalize(line.manufacturerHint)} equals
 *       {@code normalize(candidate.manufacturer)}, both non-blank (R5.3). A blank hint cannot reach a
 *       manufacturer-bearing tier.</li>
 *   <li><strong>Category match</strong> — {@code normalize(rawName).contains(normalize(candidate.category))}
 *       (containment; the receipt line rarely names the category verbatim, R5.2).</li>
 *   <li><strong>Name match</strong> — {@code normalize(rawName).contains(normalize(name))} for any of
 *       the candidate's localized names (R5.4).</li>
 *   <li><strong>Empty candidate set</strong> — every line → {@code NONE}/{@code UNMATCHED}, no error (R4.2).</li>
 * </ul>
 *
 * <p><strong>Tie-break (R5.6).</strong> When more than one candidate qualifies at the winning tier,
 * a single deterministic winner is chosen by (1) the <em>highest name similarity</em> — the largest
 * Jaccard overlap of the whitespace tokens of {@code normalize(rawName)} and the candidate's best
 * normalized name — then (2) the <em>lowest {@code materialId}</em> as the final stable key.
 *
 * <p><strong>Normalization (R5.7).</strong> {@link #normalize(String)}: Unicode NFD decomposition,
 * strip combining diacritical marks, explicit {@code ł→l}/{@code Ł→L}, lower-case (root locale),
 * collapse internal whitespace runs to a single space, and trim. So {@code "Śnieżka"} and
 * {@code "sniezka"} fold equal and case/spacing never matter.
 *
 * <p>This component is <strong>pure, total, and deterministic</strong>: it performs no persistence
 * and no per-element I/O, holds no mutable state, and its result depends only on its arguments —
 * so it is both unit- and property-testable (R5.1). It is a Spring {@code @Component} so services
 * can inject it, but every method is available {@code static}.
 */
@Component
public final class MatchEngine {

    /** Combining diacritical marks (Unicode category Mn), stripped after NFD decomposition. */
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    /** Runs of whitespace collapsed to a single space. */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /**
     * Matches one {@link ExtractedLine} against the candidate set, returning the highest-reachable
     * tier and its deterministic winner (design §5.3). Never throws for a well-formed line; an empty
     * or {@code null} candidate set yields {@code NONE}/{@code UNMATCHED} (R4.2).
     */
    public MatchResult match(ExtractedLine line, List<PlannedMaterial> candidates) {
        if (line == null) {
            return unmatched();
        }
        if (candidates == null || candidates.isEmpty()) {
            return unmatched();
        }

        String normRaw = normalize(line.rawName());
        String normHint = normalize(line.manufacturerHint());

        List<PlannedMaterial> exact = new ArrayList<>();
        List<PlannedMaterial> manufacturerCategory = new ArrayList<>();
        List<PlannedMaterial> category = new ArrayList<>();

        for (PlannedMaterial candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            boolean categoryMatch = categoryMatches(normRaw, candidate);
            boolean manufacturerMatch = manufacturerMatches(normHint, candidate);
            boolean nameMatch = nameMatches(normRaw, candidate);

            if (manufacturerMatch && categoryMatch && nameMatch) {
                exact.add(candidate);
            } else if (manufacturerMatch && categoryMatch) {
                manufacturerCategory.add(candidate);
            } else if (categoryMatch) {
                category.add(candidate);
            }
        }

        if (!exact.isEmpty()) {
            return winner(exact, MatchTier.EXACT, normRaw);
        }
        if (!manufacturerCategory.isEmpty()) {
            return winner(manufacturerCategory, MatchTier.MANUFACTURER_CATEGORY, normRaw);
        }
        if (!category.isEmpty()) {
            return winner(category, MatchTier.CATEGORY, normRaw);
        }
        return unmatched();
    }

    /**
     * The R1.5 invariant helper: {@code matchedMaterialId == null ⟺ tier == NONE} (and when
     * matched, the status is {@code AUTO}; when unmatched, {@code UNMATCHED}). The service asserts
     * this before persisting each line.
     */
    public static boolean satisfiesInvariant(MatchResult result) {
        if (result == null) {
            return false;
        }
        boolean none = result.tier() == MatchTier.NONE;
        boolean nullId = result.matchedMaterialId() == null;
        if (none != nullId) {
            return false;
        }
        return none
                ? result.status() == MatchStatus.UNMATCHED
                : result.status() == MatchStatus.AUTO;
    }

    /**
     * The documented normalization (R5.7): NFD decomposition → strip combining marks →
     * explicit {@code ł→l}/{@code Ł→L} → lower-case (root locale) → collapse whitespace → trim.
     * {@code null} and blank both normalize to the empty string.
     */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        // ł / Ł have no canonical decomposition, so map them explicitly before/after NFD.
        String mapped = value.replace('ł', 'l').replace('Ł', 'L');
        String decomposed = Normalizer.normalize(mapped, Normalizer.Form.NFD);
        String stripped = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        String lower = stripped.toLowerCase(Locale.ROOT);
        String collapsed = WHITESPACE_RUN.matcher(lower).replaceAll(" ");
        return collapsed.trim();
    }

    // --- tier predicates -----------------------------------------------------------------------

    private static boolean manufacturerMatches(String normHint, PlannedMaterial candidate) {
        if (normHint.isEmpty()) {
            return false;
        }
        String normManufacturer = normalize(candidate.manufacturer());
        return !normManufacturer.isEmpty() && normHint.equals(normManufacturer);
    }

    private static boolean categoryMatches(String normRaw, PlannedMaterial candidate) {
        if (normRaw.isEmpty()) {
            return false;
        }
        String normCategory = normalize(candidate.category());
        return !normCategory.isEmpty() && normRaw.contains(normCategory);
    }

    private static boolean nameMatches(String normRaw, PlannedMaterial candidate) {
        if (normRaw.isEmpty() || candidate.names() == null) {
            return false;
        }
        for (String name : candidate.names()) {
            String normName = normalize(name);
            if (!normName.isEmpty() && normRaw.contains(normName)) {
                return true;
            }
        }
        return false;
    }

    // --- tie-break -----------------------------------------------------------------------------

    /**
     * Picks the single deterministic winner among the candidates that reached {@code tier}:
     * highest name-token Jaccard overlap against {@code normRaw}, then lowest {@code materialId}.
     */
    private static MatchResult winner(List<PlannedMaterial> qualified, MatchTier tier, String normRaw) {
        Set<String> rawTokens = tokens(normRaw);
        PlannedMaterial best = null;
        double bestOverlap = -1.0;
        long bestId = Long.MAX_VALUE;

        for (PlannedMaterial candidate : qualified) {
            double overlap = bestNameOverlap(rawTokens, candidate);
            long id = candidate.materialId() == null ? Long.MAX_VALUE : candidate.materialId();
            if (overlap > bestOverlap || (overlap == bestOverlap && id < bestId)) {
                best = candidate;
                bestOverlap = overlap;
                bestId = id;
            }
        }

        return new MatchResult(best.materialId(), tier, MatchStatus.AUTO);
    }

    /** The highest Jaccard token overlap between {@code rawTokens} and any of the candidate's names. */
    private static double bestNameOverlap(Set<String> rawTokens, PlannedMaterial candidate) {
        if (candidate.names() == null) {
            return 0.0;
        }
        double best = 0.0;
        for (String name : candidate.names()) {
            String normName = normalize(name);
            if (normName.isEmpty()) {
                continue;
            }
            double overlap = jaccard(rawTokens, tokens(normName));
            if (overlap > best) {
                best = overlap;
            }
        }
        return best;
    }

    /** Jaccard similarity of two token sets: |∩| / |∪|; defined as 0 when both are empty. */
    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / (double) union.size();
    }

    /** Whitespace tokens of an already-normalized string. */
    private static Set<String> tokens(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (String token : normalized.split(" ")) {
            if (!token.isEmpty()) {
                result.add(token);
            }
        }
        return result;
    }

    private static MatchResult unmatched() {
        return new MatchResult(null, MatchTier.NONE, MatchStatus.UNMATCHED);
    }
}
