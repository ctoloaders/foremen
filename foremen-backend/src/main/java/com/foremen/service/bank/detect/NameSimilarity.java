package com.foremen.service.bank.detect;

import com.foremen.service.bank.ingest.CounterpartyNameNormalizer;
import java.util.HashSet;
import java.util.Set;

/**
 * Pure token-Jaccard name similarity (FOR-07-04, Requirements 14.5, 14.6).
 *
 * <p>Both names are normalized with {@link CounterpartyNameNormalizer} (diacritics-insensitive,
 * legal suffixes dropped) and compared as token sets: {@code |A ∩ B| / |A ∪ B|}. Two names with no
 * tokens at all score {@code 0.0}. The measure is symmetric and equals {@code 1.0} for names with
 * equal, non-empty normalized forms.
 */
public final class NameSimilarity {

    /** Default threshold for "name ≈ counterparty" in the rule matcher. */
    public static final double DEFAULT_THRESHOLD = 0.6;

    private NameSimilarity() {
        // Utility class: no instances.
    }

    /** Returns the token Jaccard similarity of two raw names in {@code [0.0, 1.0]}. */
    public static double similarity(String a, String b) {
        return jaccard(CounterpartyNameNormalizer.tokens(a), CounterpartyNameNormalizer.tokens(b));
    }

    /** Returns {@code true} when {@link #similarity(String, String)} ≥ {@code threshold}. */
    public static boolean similar(String a, String b, double threshold) {
        return similarity(a, b) >= threshold;
    }

    /** Returns {@code true} when the names are similar at {@link #DEFAULT_THRESHOLD}. */
    public static boolean similar(String a, String b) {
        return similar(a, b, DEFAULT_THRESHOLD);
    }

    /** Jaccard similarity of two token sets; {@code 0.0} when both are empty. */
    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }
}
