package com.foremen.service.schedule;

import com.foremen.controller.model.schedule.ScheduleReadiness.State;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.Tuple;
import net.jqwik.api.Tuple.Tuple2;
import net.jqwik.api.constraints.IntRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for {@link ScheduleReadinessState#deriveState(int, int)} (FOR-05-10,
 * Requirement 12; design §"ScheduleReadinessState", Property 6).
 *
 * <p>The derivation is a pure total function — no persistence, no Spring context, no clock — so the
 * property is cheap to run over every {@code (rowCount, scheduledCount)} combination.
 *
 * <p><b>Property 6 (readiness consistency):</b> for a valid pair
 * ({@code 0 <= scheduledCount <= rowCount}),
 * {@code DONE ⇔ rowCount > 0 ∧ scheduledCount == rowCount};
 * {@code BLOCKED ⇔ scheduledCount == 0};
 * otherwise {@code PARTIAL}. The three cases are mutually exclusive and exhaustive, so exactly one
 * state is produced for every input. A second property confirms the function is total and
 * deterministic over unconstrained integers (defensive — production always passes valid pairs).
 *
 * <p>Feature: FOR-05-10-project-gantt, Property 6: Readiness consistency
 *
 * <p><b>Validates: Requirements 12.1, 12.3</b>
 */
@Tag("Feature: FOR-05-10-project-gantt, Property 6: Readiness consistency")
class ScheduleReadinessStatePropertyTest {

    // ------------------------------------------------------------------------------------------
    // Property 6: readiness consistency over every valid (rowCount, scheduledCount) combo
    //   DONE    <=> rowCount > 0 && scheduledCount == rowCount
    //   BLOCKED <=> scheduledCount == 0   (includes rowCount == 0)
    //   PARTIAL <=> otherwise
    // ------------------------------------------------------------------------------------------

    @Property(tries = 500)
    @Tag("Feature: FOR-05-10-project-gantt, Property 6: Readiness consistency")
    void readinessStateIsConsistentForValidCombos(@ForAll("validCounts") Tuple2<Integer, Integer> counts) {
        int rowCount = counts.get1();
        int scheduledCount = counts.get2();

        State state = ScheduleReadinessState.deriveState(rowCount, scheduledCount);

        boolean expectDone = rowCount > 0 && scheduledCount == rowCount;
        boolean expectBlocked = scheduledCount == 0;

        // Exactly one state is produced; verify each as a biconditional so no case overlaps or leaks.
        assertThat(state == State.DONE).isEqualTo(expectDone);
        assertThat(state == State.BLOCKED).isEqualTo(expectBlocked && !expectDone);
        assertThat(state == State.PARTIAL).isEqualTo(!expectDone && !expectBlocked);
    }

    // ------------------------------------------------------------------------------------------
    // Property 6 (totality + determinism): deriveState returns a non-null state for ANY int pair
    // and the same inputs always yield the same state.
    // ------------------------------------------------------------------------------------------

    @Property(tries = 500)
    @Tag("Feature: FOR-05-10-project-gantt, Property 6: Readiness consistency")
    void readinessStateIsTotalAndDeterministic(
            @ForAll @IntRange(min = -1000, max = 1000) int rowCount,
            @ForAll @IntRange(min = -1000, max = 1000) int scheduledCount) {

        State first = ScheduleReadinessState.deriveState(rowCount, scheduledCount);
        State second = ScheduleReadinessState.deriveState(rowCount, scheduledCount);

        assertThat(first).isNotNull();
        assertThat(second).isEqualTo(first);
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /**
     * Valid {@code (rowCount, scheduledCount)} pairs as the service produces them:
     * {@code 0 <= scheduledCount <= rowCount}. {@code rowCount} is drawn from {@code [0, 200]}, then
     * {@code scheduledCount} is drawn from {@code [0, rowCount]}, so the generator covers the empty
     * schedule, fully-scheduled, nothing-scheduled, and every partial case in between.
     */
    @Provide
    Arbitrary<Tuple2<Integer, Integer>> validCounts() {
        return Arbitraries.integers().between(0, 200).flatMap(rowCount ->
                Arbitraries.integers().between(0, rowCount)
                        .map(scheduledCount -> Tuple.of(rowCount, scheduledCount)));
    }
}
