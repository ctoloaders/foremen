package com.foremen.service.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foremen.exception.ForemenApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.time.api.Dates;

/**
 * Property-based tests for {@link TrancheGenerator} (FOR-06-07, Property 2 — "GeneratorSumAndSeq").
 *
 * <p>{@link TrancheGenerator#generate} is a pure, persistence-free, clock-free function. Across all
 * three strategies ({@code EQUAL}/{@code PERCENT}/{@code CUSTOM}) the generated set MUST:
 *
 * <ul>
 *   <li>sum to exactly {@code basisGross} (Requirements 2.2, 2.3, 2.4);</li>
 *   <li>carry only {@code <=} 2-decimal amounts (the pricing stack's monetary scale);</li>
 *   <li>number {@code seqNo} as a contiguous {@code 1..N} sequence ordered by {@code dueDate}
 *       (Requirements 2.1, 3.2, 3.3, 3.4).</li>
 * </ul>
 *
 * <p>Malformed input — {@code N < 1}, a negative amount/percentage, an absent start date, a
 * percentage list not summing to 100, or an amount list not summing to {@code basisGross} — MUST be
 * rejected with a localized {@link ForemenApiException} (Requirements 2.3, 2.4, 2.5).
 *
 * <p>Every property runs {@code >= 100} iterations and exercises the generator over in-memory
 * values with {@code new TrancheGenerator()} — no Spring context, no database.
 *
 * <p>Feature: FOR-06-07-client-payments, Property 2
 *
 * <p><b>Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 3.2, 3.3, 3.4</b>
 */
@Tag("Feature: FOR-06-07-client-payments, Property 2")
class TrancheGeneratorPropertyTest {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final TrancheGenerator generator = new TrancheGenerator();

    // ------------------------------------------------------------------------------------------
    // Property 2a (EQUAL): N equal parts sum to exactly basisGross, are <=2 decimals, and carry a
    // contiguous 1..N seqNo ordered by dueDate.
    // Validates: Requirements 2.1, 2.2, 3.2, 3.3, 3.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-06-07-client-payments, Property 2")
    void equalStrategySumsExactlyAndNumbersContiguously(
            @ForAll("nonNegativeAmount") BigDecimal basisGross,
            @ForAll @IntRange(min = 1, max = 24) int n,
            @ForAll("startDate") LocalDate startDate,
            @ForAll("steppedPeriodicity") Periodicity periodicity) {
        List<BigDecimal> parts = new ArrayList<>(n); // EQUAL ignores values; size defines N.
        for (int i = 0; i < n; i++) {
            parts.add(null);
        }

        List<GeneratedTranche> tranches =
                generator.generate(basisGross, GeneratorStrategy.EQUAL, parts, startDate, periodicity, null);

        assertSumEqualsBasis(tranches, basisGross);
        assertAtMostTwoDecimals(tranches);
        assertContiguousSeqOrderedByDueDate(tranches, n);
    }

    // ------------------------------------------------------------------------------------------
    // Property 2b (PERCENT): percentages summing to exactly 100 split basisGross to an exact sum,
    // <=2-decimal amounts, and a contiguous 1..N seqNo.
    // Validates: Requirements 2.1, 2.3, 3.2
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-06-07-client-payments, Property 2")
    void percentStrategySummingTo100SplitsExactly(
            @ForAll("nonNegativeAmount") BigDecimal basisGross,
            @ForAll("percentagesSummingTo100") List<BigDecimal> percentages,
            @ForAll("startDate") LocalDate startDate) {
        List<GeneratedTranche> tranches = generator.generate(
                basisGross, GeneratorStrategy.PERCENT, percentages, startDate, Periodicity.MONTHLY, null);

        assertSumEqualsBasis(tranches, basisGross);
        assertAtMostTwoDecimals(tranches);
        assertContiguousSeqOrderedByDueDate(tranches, percentages.size());
    }

    // ------------------------------------------------------------------------------------------
    // Property 2c (CUSTOM): an explicit amount list that sums to basisGross is accepted as-is, with
    // <=2-decimal amounts and a contiguous 1..N seqNo.
    // Validates: Requirements 2.1, 2.4, 3.2
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-06-07-client-payments, Property 2")
    void customStrategySummingToBasisIsAccepted(
            @ForAll("amountList") List<BigDecimal> amounts,
            @ForAll("startDate") LocalDate startDate,
            @ForAll("steppedPeriodicity") Periodicity periodicity) {
        BigDecimal basisGross = sum(amounts).setScale(2, RoundingMode.HALF_UP);

        List<GeneratedTranche> tranches = generator.generate(
                basisGross, GeneratorStrategy.CUSTOM, amounts, startDate, periodicity, null);

        assertSumEqualsBasis(tranches, basisGross);
        assertAtMostTwoDecimals(tranches);
        assertContiguousSeqOrderedByDueDate(tranches, amounts.size());
    }

    // ------------------------------------------------------------------------------------------
    // Property 2d (CUSTOM_DATES): explicit per-tranche due dates are assigned in order with a
    // contiguous 1..N seqNo; the sum invariant still holds.
    // Validates: Requirements 2.1, 2.4, 3.2, 3.3, 3.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-06-07-client-payments, Property 2")
    void customDatesAssignsExplicitDatesInOrder(
            @ForAll("amountList") List<BigDecimal> amounts,
            @ForAll("startDate") LocalDate anchor) {
        BigDecimal basisGross = sum(amounts).setScale(2, RoundingMode.HALF_UP);
        int n = amounts.size();
        List<LocalDate> customDates = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            customDates.add(anchor.plusDays(i));
        }

        List<GeneratedTranche> tranches = generator.generate(
                basisGross, GeneratorStrategy.CUSTOM, amounts, null, Periodicity.CUSTOM_DATES, customDates);

        assertSumEqualsBasis(tranches, basisGross);
        assertAtMostTwoDecimals(tranches);
        assertContiguousSeqOrderedByDueDate(tranches, n);
        for (int i = 0; i < n; i++) {
            assertThat(tranches.get(i).dueDate()).isEqualTo(customDates.get(i));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 2e (invalid input rejection): N<1, a negative amount, an absent start date for a
    // stepped periodicity, a percentage list not summing to 100, and an amount list not summing to
    // basisGross are each rejected with a localized ForemenApiException.
    // Validates: Requirements 2.3, 2.4, 2.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-06-07-client-payments, Property 2")
    void invalidInputsAreRejected(
            @ForAll("nonNegativeAmount") BigDecimal basisGross,
            @ForAll @IntRange(min = 1, max = 12) int n,
            @ForAll("startDate") LocalDate startDate) {
        // N < 1 (empty EQUAL part list) -> invalid.
        assertThatThrownBy(() -> generator.generate(
                        basisGross, GeneratorStrategy.EQUAL, List.of(), startDate, Periodicity.MONTHLY, null))
                .isInstanceOf(ForemenApiException.class)
                .extracting("messageCode")
                .isEqualTo("error.payment.generator.invalid");

        // Absent start date for a stepped periodicity -> invalid.
        List<BigDecimal> equalParts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            equalParts.add(null);
        }
        assertThatThrownBy(() -> generator.generate(
                        basisGross, GeneratorStrategy.EQUAL, equalParts, null, Periodicity.WEEKLY, null))
                .isInstanceOf(ForemenApiException.class)
                .extracting("messageCode")
                .isEqualTo("error.payment.generator.invalid");

        // A negative basis -> invalid.
        assertThatThrownBy(() -> generator.generate(
                        new BigDecimal("-0.01"), GeneratorStrategy.EQUAL, equalParts, startDate,
                        Periodicity.MONTHLY, null))
                .isInstanceOf(ForemenApiException.class)
                .extracting("messageCode")
                .isEqualTo("error.payment.generator.invalid");

        // A negative percentage -> invalid (shape error outranks the sum check).
        assertThatThrownBy(() -> generator.generate(
                        basisGross, GeneratorStrategy.PERCENT,
                        List.of(new BigDecimal("-10"), new BigDecimal("110")), startDate,
                        Periodicity.MONTHLY, null))
                .isInstanceOf(ForemenApiException.class)
                .extracting("messageCode")
                .isEqualTo("error.payment.generator.invalid");

        // Percentages not summing to 100 -> percentSum rejection.
        assertThatThrownBy(() -> generator.generate(
                        basisGross, GeneratorStrategy.PERCENT,
                        List.of(new BigDecimal("40"), new BigDecimal("40")), startDate,
                        Periodicity.MONTHLY, null))
                .isInstanceOf(ForemenApiException.class)
                .extracting("messageCode")
                .isEqualTo("error.payment.generator.percentSum");

        // Amounts not summing to basisGross -> amountSum rejection (basisGross+1 never matches).
        BigDecimal mismatchedBasis = basisGross.add(BigDecimal.ONE);
        assertThatThrownBy(() -> generator.generate(
                        mismatchedBasis, GeneratorStrategy.CUSTOM,
                        List.of(basisGross.max(BigDecimal.ZERO)), startDate, Periodicity.MONTHLY, null))
                .isInstanceOf(ForemenApiException.class)
                .extracting("messageCode")
                .isEqualTo("error.payment.generator.amountSum");
    }

    // ------------------------------------------------------------------------------------------
    // Shared assertions
    // ------------------------------------------------------------------------------------------

    private static void assertSumEqualsBasis(List<GeneratedTranche> tranches, BigDecimal basisGross) {
        BigDecimal total = BigDecimal.ZERO;
        for (GeneratedTranche tranche : tranches) {
            total = total.add(tranche.amountGross());
        }
        assertThat(total).isEqualByComparingTo(basisGross.setScale(2, RoundingMode.HALF_UP));
    }

    private static void assertAtMostTwoDecimals(List<GeneratedTranche> tranches) {
        assertThat(tranches)
                .allSatisfy(tranche ->
                        assertThat(tranche.amountGross().stripTrailingZeros().scale()).isLessThanOrEqualTo(2));
    }

    private static void assertContiguousSeqOrderedByDueDate(List<GeneratedTranche> tranches, int expectedN) {
        assertThat(tranches).hasSize(expectedN);
        for (int i = 0; i < tranches.size(); i++) {
            assertThat(tranches.get(i).seqNo()).isEqualTo(i + 1);
            if (i > 0) {
                assertThat(tranches.get(i).dueDate())
                        .isAfterOrEqualTo(tranches.get(i - 1).dueDate());
            }
        }
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            total = total.add(value);
        }
        return total;
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    @Provide
    Arbitrary<BigDecimal> nonNegativeAmount() {
        return Arbitraries.longs().between(0, 99_999_999)
                .map(cents -> BigDecimal.valueOf(cents, 2));
    }

    @Provide
    Arbitrary<BigDecimal> positiveAmount() {
        return Arbitraries.longs().between(1, 99_999_999)
                .map(cents -> BigDecimal.valueOf(cents, 2));
    }

    @Provide
    Arbitrary<List<BigDecimal>> amountList() {
        return positiveAmount().list().ofMinSize(1).ofMaxSize(24);
    }

    @Provide
    Arbitrary<LocalDate> startDate() {
        return Dates.dates().between(LocalDate.of(2000, 1, 1), LocalDate.of(2100, 12, 31));
    }

    @Provide
    Arbitrary<Periodicity> steppedPeriodicity() {
        return Arbitraries.of(Periodicity.WEEKLY, Periodicity.BIWEEKLY, Periodicity.MONTHLY);
    }

    /**
     * An ordered list of 1..12 non-negative, 2-decimal percentages that sums to exactly 100: pick
     * N-1 cut points in {@code [0, 10000]} cents, sort them, and take successive differences so the
     * parts always total {@code 100.00}.
     */
    @Provide
    Arbitrary<List<BigDecimal>> percentagesSummingTo100() {
        return Arbitraries.integers().between(1, 12).flatMap(n -> {
            if (n == 1) {
                return Arbitraries.just(List.of(HUNDRED.setScale(2, RoundingMode.HALF_UP)));
            }
            return Arbitraries.integers().between(0, 10_000)
                    .list().ofSize(n - 1)
                    .map(cuts -> {
                        List<Integer> sorted = new ArrayList<>(cuts);
                        sorted.sort(Integer::compareTo);
                        List<BigDecimal> parts = new ArrayList<>(n);
                        int prev = 0;
                        for (int cut : sorted) {
                            parts.add(BigDecimal.valueOf(cut - prev, 2));
                            prev = cut;
                        }
                        parts.add(BigDecimal.valueOf(10_000 - prev, 2));
                        return parts;
                    });
        });
    }

}
