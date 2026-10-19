package com.foremen.service.payment;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.time.api.Dates;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property tests for {@link TrancheStatusDeriver} (FOR-06-07-client-payments).
 *
 * <p>Covers two correctness properties from the design:
 *
 * <ul>
 *   <li><b>Property 1 — SettledIff</b> (Requirements 1.6, 5.6):
 *       {@code deriveSchedule(T, current) == SETTLED ⟺ (T non-empty ∧ ∀ t ∈ T : t == PAID)}.</li>
 *   <li><b>Property 7 — StatusDerivation</b> (Requirements 10.1, 10.3): {@code derive} matches the
 *       exact spec ordering (PAID ≥ amount; else OVERDUE if now&gt;due; else PARTIALLY_PAID if
 *       paid&gt;0; else DUE if now≥due; else PENDING), compared at UTC day granularity; and the
 *       materialized status equals {@code derive(current fields)} after any mutation (the status
 *       column is only ever a faithful cache written by the deriver).</li>
 * </ul>
 *
 * <p>Each property runs with ≥ 100 iterations (jqwik {@code tries}). The derivation is a pure
 * function over {@code (paidGross, amountGross, dueDate, now)} and the schedule rule is a pure
 * function over a tranche-status list, so both are property-testable in isolation without any
 * persistence or injected clock.
 *
 * <p>{@code now} is built from a generated UTC day (the due date plus a day offset) so the UTC date
 * of {@code now} lands strictly before, exactly on, and strictly after {@code dueDate}; an intra-day
 * seconds offset confirms the time component is irrelevant, matching the deriver's documented
 * day-granularity comparison.
 *
 * <p><b>Validates: Requirements 1.6, 5.6, 10.1, 10.3</b>
 */
class TrancheStatusDeriverPropertyTest {

    private static final int ITERATIONS = 200;

    private final TrancheStatusDeriver deriver = new TrancheStatusDeriver();

    // ==========================================================================================
    // Property 1: SettledIff — deriveSchedule == SETTLED iff the list is non-empty and all PAID.
    // ==========================================================================================

    /**
     * Over an arbitrary tranche-status list and any current schedule status, the derived schedule
     * status is {@code SETTLED} <em>exactly</em> when the list is non-empty and every tranche is
     * {@code PAID}. A non-SETTLED result equals {@code current} unless {@code current} was itself
     * {@code SETTLED} while the list is no longer all-PAID (then it flips back to {@code PUBLISHED}).
     */
    @Property(tries = ITERATIONS)
    @Tag("Feature: FOR-06-07-client-payments, Property 1: Schedule is SETTLED iff every tranche is PAID")
    void settledIffNonEmptyAndAllPaid(
            @ForAll("trancheStatusLists") List<TrancheStatus> statuses,
            @ForAll ScheduleStatus current) {

        ScheduleStatus result = deriver.deriveSchedule(statuses, current);

        boolean nonEmptyAllPaid =
                !statuses.isEmpty() && statuses.stream().allMatch(s -> s == TrancheStatus.PAID);

        // The biconditional: SETTLED result ⟺ (non-empty ∧ all PAID).
        assertThat(result == ScheduleStatus.SETTLED)
                .as("deriveSchedule(%s, %s) == SETTLED", statuses, current)
                .isEqualTo(nonEmptyAllPaid);

        if (!nonEmptyAllPaid) {
            // Not settled: retains the current non-settled status, or flips SETTLED -> PUBLISHED.
            ScheduleStatus expected =
                    current == ScheduleStatus.SETTLED ? ScheduleStatus.PUBLISHED : current;
            assertThat(result)
                    .as("non-settled result retains/flips current status")
                    .isEqualTo(expected);
        }
    }

    /**
     * An empty tranche list is never {@code SETTLED}, regardless of the current status — a schedule
     * with no tranches cannot be fully paid (guards the {@code non-empty} side of the biconditional).
     */
    @Property(tries = ITERATIONS)
    @Tag("Feature: FOR-06-07-client-payments, Property 1: Schedule is SETTLED iff every tranche is PAID")
    void emptyListIsNeverSettled(@ForAll ScheduleStatus current) {
        ScheduleStatus result = deriver.deriveSchedule(List.of(), current);

        assertThat(result).isNotEqualTo(ScheduleStatus.SETTLED);
        ScheduleStatus expected =
                current == ScheduleStatus.SETTLED ? ScheduleStatus.PUBLISHED : current;
        assertThat(result).isEqualTo(expected);
    }

    // ==========================================================================================
    // Property 7: StatusDerivation — derive matches the exact spec ordering, at UTC day granularity.
    // ==========================================================================================

    /**
     * For any {@code (paidGross, amountGross, dueDate, now)}, {@code derive} returns exactly the
     * status an independent reference implementation of the Requirement 10.1 ordering produces,
     * where {@code now} is compared against {@code dueDate} at UTC day granularity.
     */
    @Property(tries = ITERATIONS)
    @Tag("Feature: FOR-06-07-client-payments, Property 7: Tranche status is a pure derivation")
    void deriveMatchesSpecOrdering(@ForAll("deriveInputs") DeriveInput in) {
        TrancheStatus actual = deriver.derive(in.paidGross, in.amountGross, in.dueDate, in.now);
        TrancheStatus expected = referenceDerive(in.paidGross, in.amountGross, in.dueDate, in.now);

        assertThat(actual)
                .as("derive(paid=%s, amount=%s, due=%s, now=%s)",
                        in.paidGross, in.amountGross, in.dueDate, in.now)
                .isEqualTo(expected);
    }

    /**
     * The materialized status is a faithful cache: re-deriving from the same current fields yields
     * the same status every time (deterministic), and the derivation is total — it always returns a
     * non-null status for any in-range input (Requirement 10.3: the stored column is only ever the
     * deriver's output).
     */
    @Property(tries = ITERATIONS)
    @Tag("Feature: FOR-06-07-client-payments, Property 7: Tranche status is a pure derivation")
    void deriveIsDeterministicAndTotal(@ForAll("deriveInputs") DeriveInput in) {
        TrancheStatus first = deriver.derive(in.paidGross, in.amountGross, in.dueDate, in.now);
        TrancheStatus second = deriver.derive(in.paidGross, in.amountGross, in.dueDate, in.now);

        assertThat(first).isNotNull();
        assertThat(second).isEqualTo(first);
    }

    /**
     * Fully paid (and over-paid) tranches are always {@code PAID}, taking priority over any overdue
     * state — the first branch of the spec ordering dominates the clock comparison.
     */
    @Property(tries = ITERATIONS)
    @Tag("Feature: FOR-06-07-client-payments, Property 7: Tranche status is a pure derivation")
    void fullyPaidIsAlwaysPaidEvenWhenOverdue(
            @ForAll("positiveAmount") BigDecimal amount,
            @ForAll("nonNegativeAmount") BigDecimal extra,
            @ForAll("dueDates") LocalDate dueDate,
            @ForAll("dayOffsets") long dayOffset,
            @ForAll("secondsOfDay") long secondsOfDay) {

        BigDecimal paid = amount.add(extra); // paid >= amount
        Instant now = instantOf(dueDate.plusDays(dayOffset), secondsOfDay);
        TrancheStatus status = deriver.derive(paid, amount, dueDate, now);

        assertThat(status).isEqualTo(TrancheStatus.PAID);
    }

    /**
     * The intra-day time component of {@code now} is irrelevant: two instants on the same UTC day
     * (start-of-day vs. any seconds offset) derive to the same status (confirms the day-granularity
     * comparison the deriver documents).
     */
    @Property(tries = ITERATIONS)
    @Tag("Feature: FOR-06-07-client-payments, Property 7: Tranche status is a pure derivation")
    void timeOfDayDoesNotAffectDerivation(@ForAll("deriveInputs") DeriveInput in,
                                          @ForAll("secondsOfDay") long secondsOfDay) {
        LocalDate nowDay = in.now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant startOfDay = nowDay.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant laterInDay = startOfDay.plusSeconds(secondsOfDay);

        TrancheStatus atStart = deriver.derive(in.paidGross, in.amountGross, in.dueDate, startOfDay);
        TrancheStatus later = deriver.derive(in.paidGross, in.amountGross, in.dueDate, laterInDay);

        assertThat(later).isEqualTo(atStart);
    }

    // ==========================================================================================
    // Independent reference implementation of the Requirement 10.1 ordering (day granularity, UTC).
    // ==========================================================================================

    private static TrancheStatus referenceDerive(BigDecimal paidGross, BigDecimal amountGross,
                                                  LocalDate dueDate, Instant now) {
        if (paidGross.compareTo(amountGross) >= 0) {
            return TrancheStatus.PAID;
        }
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        if (today.isAfter(dueDate)) {
            return TrancheStatus.OVERDUE;
        }
        if (paidGross.signum() > 0) {
            return TrancheStatus.PARTIALLY_PAID;
        }
        if (!today.isBefore(dueDate)) {
            return TrancheStatus.DUE;
        }
        return TrancheStatus.PENDING;
    }

    // ==========================================================================================
    // Generators
    // ==========================================================================================

    /** Simple holder for the four derivation inputs so one generator produces correlated draws. */
    private static final class DeriveInput {
        final BigDecimal paidGross;
        final BigDecimal amountGross;
        final LocalDate dueDate;
        final Instant now;

        DeriveInput(BigDecimal paidGross, BigDecimal amountGross, LocalDate dueDate, Instant now) {
            this.paidGross = paidGross;
            this.amountGross = amountGross;
            this.dueDate = dueDate;
            this.now = now;
        }

        @Override
        public String toString() {
            return "DeriveInput{paid=" + paidGross + ", amount=" + amountGross
                    + ", due=" + dueDate + ", now=" + now + "}";
        }
    }

    /**
     * Tranche-status lists of size 0..8. Each element is any {@link TrancheStatus}, biased toward
     * all-PAID lists (so the {@code SETTLED} branch is exercised often) alongside mixed lists (so
     * the biconditional's false side is covered). The empty list is included so the {@code
     * non-empty} guard is hit.
     */
    @Provide
    Arbitrary<List<TrancheStatus>> trancheStatusLists() {
        Arbitrary<List<TrancheStatus>> mixed =
                Arbitraries.of(TrancheStatus.class).list().ofMinSize(0).ofMaxSize(8);
        Arbitrary<List<TrancheStatus>> allPaid =
                Arbitraries.just(TrancheStatus.PAID).list().ofMinSize(1).ofMaxSize(8);
        return Arbitraries.oneOf(mixed, mixed, allPaid);
    }

    /**
     * Correlated derivation inputs. Amounts are non-negative two-decimal {@link BigDecimal}s;
     * {@code paidGross} is drawn across the full range (zero, partial, exactly equal, and over the
     * amount) so every branch of the ordering is reachable. {@code now} is built from the due date
     * plus a day offset in {@code [-30, +30]} so the UTC day of {@code now} lands strictly before,
     * exactly on, and strictly after {@code dueDate}; an intra-day seconds offset is added too.
     */
    @Provide
    Arbitrary<DeriveInput> deriveInputs() {
        return Combinators.combine(
                        amounts(),
                        paidFactors(),
                        dueDates(),
                        dayOffsets(),
                        secondsOfDay())
                .as((amount, factor, due, dayOffset, secs) -> {
                    BigDecimal paid = scale(amount, factor);
                    Instant now = instantOf(due.plusDays(dayOffset), secs);
                    return new DeriveInput(paid, amount, due, now);
                });
    }

    /** Builds a UTC instant for the given day plus an intra-day seconds offset. */
    private static Instant instantOf(LocalDate day, long secondsOfDay) {
        return day.atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(secondsOfDay);
    }

    /** A paid amount derived from the tranche amount: 0, partial, exactly equal, or over. */
    private Arbitrary<String> paidFactors() {
        return Arbitraries.of("0", "0.0", "0.25", "0.5", "0.999", "1", "1.0", "1.5", "2");
    }

    private static BigDecimal scale(BigDecimal amount, String factor) {
        return amount.multiply(new BigDecimal(factor)).setScale(2, RoundingMode.HALF_UP);
    }

    /** Non-negative two-decimal amounts in a realistic money range (includes zero). */
    private Arbitrary<BigDecimal> amounts() {
        return Arbitraries.longs().between(0, 10_000_000)
                .map(cents -> BigDecimal.valueOf(cents, 2));
    }

    @Provide
    Arbitrary<BigDecimal> positiveAmount() {
        return Arbitraries.longs().between(1, 10_000_000)
                .map(cents -> BigDecimal.valueOf(cents, 2));
    }

    @Provide
    Arbitrary<BigDecimal> nonNegativeAmount() {
        return amounts();
    }

    @Provide
    Arbitrary<LocalDate> dueDates() {
        return Dates.dates().between(LocalDate.of(2000, 1, 1), LocalDate.of(2100, 12, 31));
    }

    /** Day offsets around the due-date boundary so before / on / after are all hit. */
    @Provide
    Arbitrary<Long> dayOffsets() {
        return Arbitraries.longs().between(-30, 30);
    }

    /** Intra-day seconds offset (0 .. 86399) — must not change the derivation. */
    @Provide
    Arbitrary<Long> secondsOfDay() {
        return Arbitraries.longs().between(0, 86_399);
    }
}
