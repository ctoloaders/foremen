package com.foremen.service.bank.ingest;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared, pure normalizer for counterparty / holder names (FOR-07-04, Requirements 6.5, 14.5).
 *
 * <p>Used by {@link CounterpartyExtractor} to build the {@code NAME:} counterparty key, by
 * {@code NameSimilarity} for token matching, and by {@code HolderMatcher} for account holder
 * verification, so that all three compare names identically.
 *
 * <p>Steps: lower-case; strip diacritics (incl. {@code ł}, which has no Unicode decomposition);
 * replace every non-alphanumeric character with a space; collapse whitespace; drop trailing Polish
 * legal-form suffixes ({@code sp. z o.o.}, {@code s.a.}, {@code sp.j.}, {@code sp.k.}, {@code s.c.}
 * and their spelled-out forms). A suffix is never dropped when it would leave an empty name. The
 * function is idempotent.
 */
public final class CounterpartyNameNormalizer {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    /** Legal-form suffixes as normalized token sequences (longest first). */
    private static final List<List<String>> LEGAL_SUFFIXES = List.of(
            List.of("spolka", "z", "ograniczona", "odpowiedzialnoscia"),
            List.of("spolka", "z", "o", "o"),
            List.of("sp", "z", "o", "o"),
            List.of("spolka", "z", "oo"),
            List.of("sp", "z", "oo"),
            List.of("spolka", "akcyjna"),
            List.of("spolka", "jawna"),
            List.of("spolka", "komandytowa"),
            List.of("spolka", "cywilna"),
            List.of("sp", "j"),
            List.of("sp", "k"),
            List.of("s", "a"),
            List.of("s", "c"),
            List.of("spj"),
            List.of("spk"));

    private CounterpartyNameNormalizer() {
        // Utility class: no instances.
    }

    /**
     * Normalizes a name.
     *
     * @param name raw name, may be {@code null}
     * @return the normalized name (space-separated tokens), or an empty string for null / blank /
     *     punctuation-only input
     */
    public static String normalize(String name) {
        return String.join(" ", tokenList(name));
    }

    /** Returns the distinct normalized tokens of a name, in order of first appearance. */
    public static Set<String> tokens(String name) {
        return new LinkedHashSet<>(tokenList(name));
    }

    private static List<String> tokenList(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        String folded = stripDiacritics(name.toLowerCase(Locale.ROOT));
        String spaced = NON_ALNUM.matcher(folded).replaceAll(" ").trim();
        if (spaced.isEmpty()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>(Arrays.asList(spaced.split(" ")));
        boolean dropped = true;
        while (dropped) {
            dropped = false;
            for (List<String> suffix : LEGAL_SUFFIXES) {
                if (tokens.size() > suffix.size() && endsWith(tokens, suffix)) {
                    tokens.subList(tokens.size() - suffix.size(), tokens.size()).clear();
                    dropped = true;
                    break;
                }
            }
        }
        return tokens;
    }

    private static boolean endsWith(List<String> tokens, List<String> suffix) {
        int offset = tokens.size() - suffix.size();
        for (int i = 0; i < suffix.size(); i++) {
            if (!tokens.get(offset + i).equals(suffix.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static String stripDiacritics(String lower) {
        String replaced = lower.replace('ł', 'l').replace('đ', 'd').replace('ø', 'o').replace("ß", "ss");
        String decomposed = Normalizer.normalize(replaced, Normalizer.Form.NFD);
        return COMBINING_MARKS.matcher(decomposed).replaceAll("");
    }
}
