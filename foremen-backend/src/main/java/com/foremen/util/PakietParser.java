package com.foremen.util;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * FOR-04-18 (Requirement 6.4) — pure normalization of the multi-value {@code Pakiet} CSV cell.
 *
 * <p>This is the Java mirror of the {@code parse_packages} normalization in the Python seed
 * generator ({@code database_files/generators/gen_finishing_materials_seed.py}). It performs
 * exactly the same token normalization, but returns the normalized package <b>tokens</b>
 * ({@code Budget}/{@code Standard}/{@code Lux}) rather than the {@code offer_packages} codes the
 * generator ultimately resolves to. Keeping this normalization in a small pure utility lets the
 * property test exercise the identical rules with no Spring/DB.
 *
 * <p>Normalization rules (mirroring the generator exactly):
 * <ul>
 *   <li>split the cell on commas into tokens;</li>
 *   <li>per token: trim whitespace, strip surrounding double-quotes, strip a leading {@code "+ "}
 *       (or bare leading {@code "+"}) marker, then trim quotes/whitespace again;</li>
 *   <li>map the misspelling {@code Standart} to {@code Standard} (case-insensitive on the token);</li>
 *   <li>drop empty tokens;</li>
 *   <li>collapse duplicates (a {@link Set} with insertion order preserved).</li>
 * </ul>
 */
public final class PakietParser {

    private PakietParser() {
    }

    /**
     * Normalizes a raw {@code Pakiet} cell into its set of package tokens.
     *
     * @param cell the raw CSV {@code Pakiet} cell (may be {@code null} or blank)
     * @return the normalized, duplicate-collapsed set of package tokens
     *         ({@code Budget}/{@code Standard}/{@code Lux}); never {@code null}
     */
    public static Set<String> parse(String cell) {
        Set<String> tokens = new LinkedHashSet<>();
        if (cell == null || cell.isBlank()) {
            return tokens;
        }
        for (String raw : cell.split(",")) {
            String tok = normalizeToken(raw);
            if (tok.isEmpty()) {
                continue;
            }
            tokens.add(canonical(tok));
        }
        return tokens;
    }

    /** Strips whitespace, surrounding double-quotes and a leading {@code "+ "}/{@code "+"} marker. */
    private static String normalizeToken(String raw) {
        String tok = stripQuotesAndTrim(raw);
        if (tok.startsWith("+ ")) {
            tok = tok.substring(2).trim();
        } else if (tok.startsWith("+")) {
            tok = tok.substring(1).trim();
        }
        return stripQuotesAndTrim(tok);
    }

    /** Trims whitespace, then strips leading/trailing double-quote characters, then trims again. */
    private static String stripQuotesAndTrim(String value) {
        String v = value.trim();
        int start = 0;
        int end = v.length();
        while (start < end && v.charAt(start) == '"') {
            start++;
        }
        while (end > start && v.charAt(end - 1) == '"') {
            end--;
        }
        return v.substring(start, end).trim();
    }

    /** Maps the {@code Standart} misspelling to {@code Standard}; leaves other tokens untouched. */
    private static String canonical(String token) {
        if (token.equalsIgnoreCase("Standart")) {
            return "Standard";
        }
        return token;
    }
}
