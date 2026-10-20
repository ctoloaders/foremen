package com.foremen.service.execution.amendment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.foremen.dao.model.AmendmentLineEntity;
import com.foremen.dao.model.ChangeType;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

/**
 * FOR-06-09 (Requirements 5.1, 5.3) — property-based coverage of the pure, persistence-free
 * {@link ScheduleDeltaCalculator}, which owns the arithmetic behind the single schedule mutation an
 * {@code EFFECTIVE} amendment performs (shifting {@code Project.endDate}). The calculator holds no
 * state and does no I/O, so every rule is exercised in isolation with no Spring / persistence context
 * (design §"ScheduleDeltaCalculator (pure)").
 *
 * <p><b>Property 5: Net schedule delta is a pure sum over SCHEDULE_CHANGE lines.</b>
 * <ul>
 *   <li>{@code netHalfDays(L) == Σ scheduleDeltaHalfDays} over the {@code SCHEDULE_CHANGE} lines,
 *       skipping null deltas and ignoring cost lines entirely.</li>
 *   <li>{@code shift(e, n) == e.plusDays(round(n / 2.0))} on the FOR-06-08 half-day grid.</li>
 *   <li>The zero case — no {@code SCHEDULE_CHANGE} line (or net delta 0) — leaves the end date
 *       unchanged.</li>
 * </ul>
 *
 * <p>Feature: FOR-06-09-amendment-signing, Property 5
 *
 * <p><b>Validates: Requirements 5.1, 5.3</b>
 */
@Tag("Feature: FOR-06-09-amendment-signing, Property 5")
class ScheduleDeltaCalculatorPropertyTest {

    private final ScheduleDeltaCalculator calculator = new ScheduleDeltaCalculator();

    // --- Property 5a: netHalfDays is Σ of SCHEDULE_CHANGE deltas, skipping nulls & cost lines ----

    /**
     * // Feature: FOR-06-09-amendment-signing, Property 5
     *
     * <p>{@code netHalfDays(lines)} equals the plain integer sum of {@code scheduleDeltaHalfDays} over
     * exactly the {@code SCHEDULE_CHANGE} lines whose delta is non-null — cost lines
     * ({@code ADDITIONAL_WORK} / {@code VOLUME_CHANGE}) and {@code SCHEDULE_CHANGE} lines with a
     * {@code null} delta contribute nothing (Requirement 5.1).
     */
    @Property(tries = 300)
    @Tag("Feature: FOR-06-09-amendment-signing, Property 5")
    void netHalfDaysIsSumOverScheduleChangeLines(
            @ForAll("mixedLines") @Size(max = 12) List<AmendmentLineEntity> lines) {

        int expected = 0;
        for (AmendmentLineEntity line : lines) {
            if (line != null
                    && line.getChangeType() == ChangeType.SCHEDULE_CHANGE
                    && line.getScheduleDeltaHalfDays() != null) {
                expected += line.getScheduleDeltaHalfDays();
            }
        }

        assertThat(calculator.netHalfDays(lines)).isEqualTo(expected);
    }

    /**
     * // Feature: FOR-06-09-amendment-signing, Property 5
     *
     * <p>Order-insensitivity: summation is commutative, so reversing the line order does not change
     * the net half-day total (Requirement 5.1).
     */
    @Property(tries = 300)
    @Tag("Feature: FOR-06-09-amendment-signing, Property 5")
    void netHalfDaysIsOrderInsensitive(
            @ForAll("mixedLines") @Size(max = 12) List<AmendmentLineEntity> lines) {

        List<AmendmentLineEntity> reversed = new ArrayList<>(lines);
        java.util.Collections.reverse(reversed);

        assertThat(calculator.netHalfDays(reversed)).isEqualTo(calculator.netHalfDays(lines));
    }

    // --- Property 5b: shift converts half-days to days via round(n / 2.0) -----------------------

    /**
     * // Feature: FOR-06-09-amendment-signing, Property 5
     *
     * <p>{@code shift(endDate, netHalfDays) == endDate.plusDays(round(netHalfDays / 2.0))} — the
     * half-day grid conversion (Requirement 5.1).
     */
    @Property(tries = 300)
    @Tag("Feature: FOR-06-09-amendment-signing, Property 5")
    void shiftAddsRoundedHalfDaysToEndDate(
            @ForAll("endDate") LocalDate endDate,
            @ForAll @IntRange(min = -10_000, max = 10_000) int netHalfDays) {

        LocalDate expected = endDate.plusDays(Math.round(netHalfDays / 2.0));

        assertThat(calculator.shift(endDate, netHalfDays)).isEqualTo(expected);
    }

    // --- Property 5c: the zero case leaves the end date unchanged -------------------------------

    /**
     * // Feature: FOR-06-09-amendment-signing, Property 5
     *
     * <p>A list with no {@code SCHEDULE_CHANGE} line (only cost / null-delta lines) nets to {@code 0},
     * and a zero shift leaves the end date unchanged (Requirement 5.3).
     */
    @Property(tries = 300)
    @Tag("Feature: FOR-06-09-amendment-signing, Property 5")
    void noScheduleChangeLeavesEndDateUnchanged(
            @ForAll("nonScheduleLines") @Size(max = 10) List<AmendmentLineEntity> lines,
            @ForAll("endDate") LocalDate endDate) {

        int net = calculator.netHalfDays(lines);

        assertThat(net).isZero();
        assertThat(calculator.shift(endDate, net)).isEqualTo(endDate);
    }

    /**
     * // Feature: FOR-06-09-amendment-signing, Property 5
     *
     * <p>A null or empty line list nets to {@code 0} and leaves the end date unchanged
     * (Requirement 5.3).
     */
    @Property(tries = 100)
    @Tag("Feature: FOR-06-09-amendment-signing, Property 5")
    void nullOrEmptyLinesNetToZero(@ForAll("endDate") LocalDate endDate) {
        assertThat(calculator.netHalfDays(null)).isZero();
        assertThat(calculator.netHalfDays(List.of())).isZero();
        assertThat(calculator.shift(endDate, calculator.netHalfDays(List.of()))).isEqualTo(endDate);
    }

    // --- Generators -----------------------------------------------------------------------------

    /** A signed half-day delta, bounded well clear of int overflow across sums. */
    @Provide
    Arbitrary<Integer> halfDays() {
        return Arbitraries.integers().between(-5_000, 5_000);
    }

    /** A valid project end date within a wide but overflow-safe calendar window. */
    @Provide
    Arbitrary<LocalDate> endDate() {
        return Arbitraries.longs()
                .between(LocalDate.of(2000, 1, 1).toEpochDay(), LocalDate.of(2100, 12, 31).toEpochDay())
                .map(LocalDate::ofEpochDay);
    }

    /**
     * A mix of line kinds: {@code SCHEDULE_CHANGE} lines with a non-null delta, {@code SCHEDULE_CHANGE}
     * lines with a {@code null} delta (must be skipped), cost lines (must be ignored), and occasional
     * {@code null} entries (must be tolerated).
     */
    @Provide
    Arbitrary<List<AmendmentLineEntity>> mixedLines() {
        Arbitrary<AmendmentLineEntity> scheduleWithDelta =
                halfDays().map(this::scheduleLine);
        Arbitrary<AmendmentLineEntity> scheduleNullDelta =
                Arbitraries.just(scheduleLine(null));
        Arbitrary<AmendmentLineEntity> cost =
                Arbitraries.of(ChangeType.ADDITIONAL_WORK, ChangeType.VOLUME_CHANGE)
                        .map(this::costLine);
        Arbitrary<AmendmentLineEntity> nullEntry = Arbitraries.just(null);

        return Arbitraries.oneOf(scheduleWithDelta, scheduleNullDelta, cost, nullEntry).list();
    }

    /** Only lines that must contribute nothing to the net delta (cost + null-delta schedule). */
    @Provide
    Arbitrary<List<AmendmentLineEntity>> nonScheduleLines() {
        Arbitrary<AmendmentLineEntity> cost =
                Arbitraries.of(ChangeType.ADDITIONAL_WORK, ChangeType.VOLUME_CHANGE)
                        .map(this::costLine);
        Arbitrary<AmendmentLineEntity> scheduleNullDelta =
                Arbitraries.just(scheduleLine(null));
        return Arbitraries.oneOf(cost, scheduleNullDelta).list();
    }

    // --- Line builders --------------------------------------------------------------------------

    private AmendmentLineEntity scheduleLine(Integer halfDays) {
        AmendmentLineEntity line = new AmendmentLineEntity();
        line.setChangeType(ChangeType.SCHEDULE_CHANGE);
        line.setScheduleDeltaHalfDays(halfDays);
        return line;
    }

    private AmendmentLineEntity costLine(ChangeType type) {
        AmendmentLineEntity line = new AmendmentLineEntity();
        line.setChangeType(type);
        // A cost line may still carry a schedule-delta value defensively; it must be ignored because
        // the change type is not SCHEDULE_CHANGE.
        line.setScheduleDeltaHalfDays(999);
        return line;
    }
}
