package com.foremen.service.payroll;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.service.payroll.PayrollHintSelector.Line;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * FOR-07-06 (task 2.4; design §Correctness properties, Property 9 "Hints") — property-based tests
 * for the pure {@link PayrollHintSelector}.
 *
 * <p>Hints ⊆ previous-month lines; none has a (trimmed, case-insensitive) name present in the
 * current month; each eligible name is hinted exactly once, by its latest line.
 *
 * <p><b>Validates: Requirements 8.5</b>
 */
@Tag("Feature: FOR-07-06-company-payroll, Property 9: hints")
class PayrollHintSelectorPropertyTest {

    private static final List<String> BASE_NAMES = List.of("Multisport", "Dodatek", "Potrącenie", "Bonus", "Paliwo");

    /** A base name with random case and surrounding whitespace, so normalization is exercised. */
    private static Arbitrary<String> names() {
        return Combinators.combine(
                        Arbitraries.of(BASE_NAMES),
                        Arbitraries.of(0, 1, 2),
                        Arbitraries.of("", " ", "  ", "\t"),
                        Arbitraries.of("", " ", "  "))
                .as((n, c, pre, post) -> pre + switch (c) {
                    case 0 -> n;
                    case 1 -> n.toUpperCase(java.util.Locale.ROOT);
                    default -> n.toLowerCase(java.util.Locale.ROOT);
                } + post);
    }

    private static Arbitrary<List<Line>> lines(long idBase) {
        return Combinators.combine(
                        names(),
                        Arbitraries.longs().between(-100_000, 100_000).filter(v -> v != 0).map(c -> BigDecimal.valueOf(c, 2)),
                        Arbitraries.longs().between(0, 50).injectNull(0.1))
                .as((n, a, t) -> new Object[]{n, a, t})
                .list().ofMaxSize(12)
                .map(raw -> IntStream.range(0, raw.size())
                        .mapToObj(i -> {
                            Object[] r = raw.get(i);
                            Long t = (Long) r[2];
                            return new Line(idBase + i, (String) r[0], (BigDecimal) r[1],
                                    t == null ? null : Instant.parse("2025-06-01T00:00:00Z").plusSeconds(t));
                        })
                        .toList());
    }

    @Provide
    Arbitrary<List<Line>> previous() {
        return lines(1_000);
    }

    @Provide
    Arbitrary<List<Line>> current() {
        return lines(9_000).map(l -> l.size() > 4 ? l.subList(0, 4) : l);
    }

    @Property(tries = 500)
    void hintsAreLatestPreviousLinesWithNamesAbsentInCurrentMonth(@ForAll("previous") List<Line> previous,
                                                                   @ForAll("current") List<Line> current) {
        List<Line> hints = PayrollHintSelector.hints(previous, current);

        Set<String> currentNames = current.stream().map(l -> PayrollHintSelector.normalize(l.name()))
                .collect(Collectors.toSet());

        // subset of previous month
        assertThat(previous).containsAll(hints);
        // none present in the current month
        assertThat(hints).noneMatch(h -> currentNames.contains(PayrollHintSelector.normalize(h.name())));
        // one hint per normalized name
        List<String> hintNames = hints.stream().map(h -> PayrollHintSelector.normalize(h.name())).toList();
        assertThat(hintNames).doesNotHaveDuplicates();
        // every eligible name is represented
        Set<String> eligible = previous.stream().map(l -> PayrollHintSelector.normalize(l.name()))
                .filter(n -> !currentNames.contains(n)).collect(Collectors.toSet());
        assertThat(hintNames).containsExactlyInAnyOrderElementsOf(eligible);
        // each hint is the latest of its name
        for (Line h : hints) {
            String key = PayrollHintSelector.normalize(h.name());
            Line latest = previous.stream().filter(l -> PayrollHintSelector.normalize(l.name()).equals(key))
                    .max(PayrollHintSelector.LATEST_LAST).orElseThrow();
            assertThat(h).isEqualTo(latest);
        }
        // ordered oldest → latest
        assertThat(hints).isSortedAccordingTo(PayrollHintSelector.LATEST_LAST);
    }

    @Property(tries = 200)
    void emptyCurrentMonthHintsEveryPreviousName(@ForAll("previous") List<Line> previous) {
        List<Line> hints = PayrollHintSelector.hints(previous, List.of());
        long distinctNames = previous.stream().map(l -> PayrollHintSelector.normalize(l.name())).distinct().count();
        assertThat(hints).hasSize((int) distinctNames);
    }

    @Example
    void trimmedCaseInsensitiveMatchAndLatestWins() {
        Instant t0 = Instant.parse("2025-06-01T10:00:00Z");
        List<Line> previous = List.of(
                new Line(1L, "Multisport", new BigDecimal("150.00"), t0),
                new Line(2L, "  bonus ", new BigDecimal("100.00"), t0),
                new Line(3L, "BONUS", new BigDecimal("200.00"), t0.plusSeconds(60)),
                new Line(4L, "Dodatek", new BigDecimal("50.00"), t0));
        List<Line> current = List.of(new Line(10L, " multisport", new BigDecimal("150.00"), t0));

        List<Line> hints = PayrollHintSelector.hints(previous, current);

        assertThat(hints).extracting(Line::id).containsExactly(4L, 3L);
    }

    @Example
    void sameTimestampTieBrokenByIdAndNullInputsAreEmpty() {
        Instant t = Instant.parse("2025-06-01T10:00:00Z");
        List<Line> previous = List.of(
                new Line(7L, "Paliwo", new BigDecimal("10.00"), t),
                new Line(5L, "paliwo", new BigDecimal("20.00"), t));
        assertThat(PayrollHintSelector.hints(previous, null)).extracting(Line::id).containsExactly(7L);
        assertThat(PayrollHintSelector.hints(null, previous)).isEmpty();
    }
}
