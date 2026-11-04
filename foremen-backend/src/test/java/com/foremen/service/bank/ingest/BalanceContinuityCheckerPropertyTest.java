package com.foremen.service.bank.ingest;

import com.foremen.service.bank.ingest.BalanceContinuityChecker.Gap;
import com.foremen.service.bank.ingest.BalanceContinuityChecker.PostedAmount;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 8 (FOR-07-04): balance check soundness — a gap is reported iff
 * {@code |prev + Σ(window) − new| > 0.01}; never when a balance is missing and never for CASH.
 *
 * <p><b>Validates: Requirements 7.1, 7.2, 7.4</b>
 */
@Tag("Feature: FOR-07-04-bank-ledger, Property 8: Balance check soundness")
class BalanceContinuityCheckerPropertyTest {

    private static final OffsetDateTime PREV_AT = OffsetDateTime.of(2026, 3, 10, 14, 0, 0, 0, ZoneOffset.UTC);

    @Property(tries = 300)
    void gapIffMismatch(@ForAll("money") BigDecimal prev,
                        @ForAll("postedLists") List<PostedAmount> posted,
                        @ForAll @IntRange(min = 0, max = 30) int daysAhead,
                        @ForAll("offsets") BigDecimal offset) {
        OffsetDateTime newAt = PREV_AT.plusDays(daysAhead);
        BigDecimal windowSum = referenceWindowSum(posted, PREV_AT.toLocalDate(), newAt.toLocalDate());
        BigDecimal expected = prev.add(windowSum);
        BigDecimal actual = expected.add(offset);

        Optional<Gap> gap = BalanceContinuityChecker.check(prev, PREV_AT, actual, newAt, posted);

        boolean mismatch = offset.abs().compareTo(new BigDecimal("0.01")) > 0;
        assertThat(gap.isPresent()).isEqualTo(mismatch);
        gap.ifPresent(g -> {
            assertThat(g.expected()).isEqualByComparingTo(expected);
            assertThat(g.actual()).isEqualByComparingTo(actual);
            assertThat(g.difference()).isEqualByComparingTo(offset);
        });
    }

    @Property(tries = 100)
    void missingBalanceNeverReportsGap(@ForAll("money") BigDecimal balance,
                                       @ForAll("postedLists") List<PostedAmount> posted,
                                       @ForAll boolean missingPrevious) {
        OffsetDateTime newAt = PREV_AT.plusDays(5);
        Optional<Gap> gap = missingPrevious
                ? BalanceContinuityChecker.check(null, PREV_AT, balance, newAt, posted)
                : BalanceContinuityChecker.check(balance, PREV_AT, null, newAt, posted);
        assertThat(gap).isEmpty();
    }

    @Property(tries = 100)
    void cashAccountNeverReportsGap(@ForAll("money") BigDecimal prev,
                                    @ForAll("money") BigDecimal actual,
                                    @ForAll("postedLists") List<PostedAmount> posted) {
        assertThat(BalanceContinuityChecker.check(true, prev, PREV_AT, actual, PREV_AT.plusDays(3), posted))
                .isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // Unit examples: window boundaries and the tolerance edge.
    // ---------------------------------------------------------------------------------------

    @net.jqwik.api.Example
    void windowExcludesPreviousDateAndIncludesNewDate() {
        OffsetDateTime newAt = PREV_AT.plusDays(2);
        List<PostedAmount> posted = List.of(
                new PostedAmount(PREV_AT.toLocalDate(), new BigDecimal("100.00")),          // excluded
                new PostedAmount(PREV_AT.toLocalDate().plusDays(1), new BigDecimal("-20.00")),
                new PostedAmount(newAt.toLocalDate(), new BigDecimal("5.00")),              // included
                new PostedAmount(newAt.toLocalDate().plusDays(1), new BigDecimal("999.00"))); // excluded
        assertThat(BalanceContinuityChecker.check(new BigDecimal("1000.00"), PREV_AT,
                new BigDecimal("985.00"), newAt, posted)).isEmpty();
    }

    @net.jqwik.api.Example
    void exactlyOneCentIsNotAGapButMoreIs() {
        OffsetDateTime newAt = PREV_AT.plusDays(1);
        assertThat(BalanceContinuityChecker.check(new BigDecimal("10.00"), PREV_AT,
                new BigDecimal("10.01"), newAt, List.of())).isEmpty();
        Optional<Gap> gap = BalanceContinuityChecker.check(new BigDecimal("10.00"), PREV_AT,
                new BigDecimal("9.98"), newAt, List.of());
        assertThat(gap).isPresent();
        assertThat(gap.get().difference()).isEqualByComparingTo("-0.02");
        assertThat(gap.get().expected()).isEqualByComparingTo("10.00");
    }

    // ---------------------------------------------------------------------------------------

    private static BigDecimal referenceWindowSum(List<PostedAmount> posted, LocalDate from, LocalDate to) {
        return posted.stream()
                .filter(p -> p.bookingDate().compareTo(from) > 0 && p.bookingDate().compareTo(to) <= 0)
                .map(PostedAmount::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Provide
    Arbitrary<BigDecimal> money() {
        return Arbitraries.bigDecimals().between(new BigDecimal("-1000000"), new BigDecimal("1000000"))
                .ofScale(2);
    }

    /** Offsets around the tolerance edge plus large ones. */
    @Provide
    Arbitrary<BigDecimal> offsets() {
        return Arbitraries.oneOf(
                Arbitraries.of(new BigDecimal("0.00"), new BigDecimal("0.01"), new BigDecimal("-0.01"),
                        new BigDecimal("0.02"), new BigDecimal("-0.02"), new BigDecimal("0.011"),
                        new BigDecimal("-0.011"), new BigDecimal("0.005")),
                Arbitraries.bigDecimals().between(new BigDecimal("-500"), new BigDecimal("500")).ofScale(2));
    }

    @Provide
    Arbitrary<List<PostedAmount>> postedLists() {
        Arbitrary<LocalDate> dates = Arbitraries.integers().between(-5, 40)
                .map(d -> PREV_AT.toLocalDate().plusDays(d));
        Arbitrary<BigDecimal> amounts = Arbitraries.bigDecimals()
                .between(new BigDecimal("-10000"), new BigDecimal("10000")).ofScale(2);
        return net.jqwik.api.Combinators.combine(dates, amounts).as(PostedAmount::new)
                .list().ofMaxSize(15);
    }
}
