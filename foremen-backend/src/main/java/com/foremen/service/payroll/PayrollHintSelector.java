package com.foremen.service.payroll;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Pure previous-month hint filter for the company payroll card (FOR-07-06, Requirement 8.5;
 * design §PayrollLineService, PayrollHintSelector, Property 9).
 *
 * <p>Given the worker's additional lines of month M−1 and of month M, a hint is a line of M−1 whose
 * normalized name ({@code name.trim().toLowerCase(Locale.ROOT)}) does not occur among the lines of
 * M. Several M−1 lines with the same normalized name collapse to the <b>latest</b> one — the
 * greatest {@code createdAt}, ties (or missing timestamps) broken by the greatest {@code id};
 * {@code null} sorts first on both keys.
 *
 * <p>The result is ordered from the oldest to the latest surviving line (same ordering as "latest").
 * Lines with a {@code null} or blank name never match and are never hinted (names are validated
 * non-blank on write, R8.2, so this is purely defensive).
 *
 * <p>No Spring, no clock, no persistence — a deterministic function of its arguments. The service
 * maps {@code WorkerPayrollLineEntity} rows to {@link Line} (or uses the generic overload).
 */
public final class PayrollHintSelector {

    /** Minimal projection of an additional payroll line needed for hint selection. */
    public record Line(Long id, String name, BigDecimal amount, Instant createdAt) {
    }

    /** "Latest" ordering: {@code createdAt}, then {@code id}; {@code null}s first. */
    public static final Comparator<Line> LATEST_LAST = Comparator
            .comparing(Line::createdAt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()))
            .thenComparing(Line::id, Comparator.nullsFirst(Comparator.<Long>naturalOrder()));

    private PayrollHintSelector() {
    }

    /**
     * Hints for month M (R8.5).
     *
     * @param previousMonthLines the worker's lines of M−1 ({@code null} → empty)
     * @param currentMonthLines  the worker's lines of M ({@code null} → empty)
     * @return the M−1 lines to suggest, one per normalized name, oldest first
     */
    public static List<Line> hints(Collection<Line> previousMonthLines, Collection<Line> currentMonthLines) {
        return hints(previousMonthLines, currentMonthLines, Line::name, LATEST_LAST);
    }

    /**
     * Generic form, for callers that want to pass entities/DTOs directly.
     *
     * @param nameOf     the line name accessor
     * @param latestLast ordering in which the latest line is the greatest
     */
    public static <T> List<T> hints(Collection<T> previousMonthLines, Collection<T> currentMonthLines,
                                    Function<T, String> nameOf, Comparator<? super T> latestLast) {
        if (previousMonthLines == null || previousMonthLines.isEmpty()) {
            return List.of();
        }
        Set<String> currentNames = new HashSet<>();
        if (currentMonthLines != null) {
            for (T line : currentMonthLines) {
                String key = normalize(nameOf.apply(line));
                if (key != null) {
                    currentNames.add(key);
                }
            }
        }
        Map<String, T> latestByName = new LinkedHashMap<>();
        for (T line : previousMonthLines) {
            String key = normalize(nameOf.apply(line));
            if (key == null || currentNames.contains(key)) {
                continue;
            }
            latestByName.merge(key, line, (a, b) -> latestLast.compare(b, a) >= 0 ? b : a);
        }
        List<T> result = new ArrayList<>(latestByName.values());
        result.sort(latestLast);
        return List.copyOf(result);
    }

    /** {@code name.trim().toLowerCase(Locale.ROOT)}, or {@code null} for a null/blank name. */
    public static String normalize(String name) {
        if (name == null) {
            return null;
        }
        String key = name.trim().toLowerCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }
}
