package com.foremen.service.readiness;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link ReadinessMath} (FOR-05-11, Properties 2, 3, 4).
 *
 * <ul>
 *   <li><b>Property 2 — fallback mapping.</b> {@link ReadinessMath#fallbackPercentage} returns
 *       exactly {@code 100 / 50 / 0} for {@code DONE / PARTIAL / BLOCKED} (Requirement 1.5).</li>
 *   <li><b>Property 3 — equal-weight average.</b> {@link ReadinessMath#overallPercentage} equals
 *       {@code round(sum / n)}, is {@code 0} for the empty list, and is permutation-invariant
 *       (Requirement 4.3).</li>
 *   <li><b>Property 4 — state roll-up.</b> {@link ReadinessMath#overallState} is {@code BLOCKED}
 *       iff any is {@code BLOCKED}, else {@code PARTIAL} iff any is {@code PARTIAL}, else
 *       {@code DONE}; empty rolls up to {@code BLOCKED}; and it is permutation-invariant
 *       (Requirement 4.4).</li>
 * </ul>
 *
 * <p>Feature: FOR-05-11-project-readiness, Properties 2, 3, 4
 *
 * <p><b>Validates: Requirements 1.5, 4.3, 4.4</b>
 */
@Tag("Feature: FOR-05-11-project-readiness, Properties 2, 3, 4")
class ReadinessMathPropertyTest {

    // --- Property 2: fallback mapping (Requirement 1.5) ---

    @Property(tries = 100)
    void fallbackPercentageMapsEachStateToItsCanonicalValue(@ForAll ReadinessState state) {
        int expected = switch (state) {
            case DONE -> 100;
            case PARTIAL -> 50;
            case BLOCKED -> 0;
        };

        assertThat(ReadinessMath.fallbackPercentage(state)).isEqualTo(expected);
    }

    // --- Property 3: equal-weight average (Requirement 4.3) ---

    @Property(tries = 200)
    void overallPercentageEqualsRoundedMean(@ForAll("contributions") List<AreaContribution> included) {
        int actual = ReadinessMath.overallPercentage(included);

        if (included.isEmpty()) {
            assertThat(actual).isZero();
            return;
        }

        long sum = included.stream().mapToLong(AreaContribution::percentage).sum();
        int expected = (int) Math.round((double) sum / included.size());

        assertThat(actual).isEqualTo(expected);
        assertThat(actual).isBetween(0, 100);
    }

    @Property(tries = 200)
    void overallPercentageIsPermutationInvariant(@ForAll("nonEmptyContributions") List<AreaContribution> included) {
        List<AreaContribution> shuffled = reversed(included);

        assertThat(ReadinessMath.overallPercentage(shuffled))
                .isEqualTo(ReadinessMath.overallPercentage(included));
    }

    // --- Property 4: state roll-up (Requirement 4.4) ---

    @Property(tries = 200)
    void overallStateRollsUpBlockedOverPartialOverDone(@ForAll("contributions") List<AreaContribution> included) {
        ReadinessState actual = ReadinessMath.overallState(included);

        if (included.isEmpty()) {
            assertThat(actual).isEqualTo(ReadinessState.BLOCKED);
            return;
        }

        boolean anyBlocked = included.stream().anyMatch(c -> c.state() == ReadinessState.BLOCKED);
        boolean anyPartial = included.stream().anyMatch(c -> c.state() == ReadinessState.PARTIAL);

        ReadinessState expected = anyBlocked
                ? ReadinessState.BLOCKED
                : (anyPartial ? ReadinessState.PARTIAL : ReadinessState.DONE);

        assertThat(actual).isEqualTo(expected);
    }

    @Property(tries = 200)
    void overallStateIsPermutationInvariant(@ForAll("nonEmptyContributions") List<AreaContribution> included) {
        List<AreaContribution> shuffled = reversed(included);

        assertThat(ReadinessMath.overallState(shuffled))
                .isEqualTo(ReadinessMath.overallState(included));
    }

    // --- Providers ---

    /** A single area contribution with a random state and a random in-range percentage. */
    @Provide
    Arbitrary<AreaContribution> contribution() {
        Arbitrary<ReadinessState> state = Arbitraries.of(ReadinessState.class);
        Arbitrary<Integer> percentage = Arbitraries.integers().between(0, 100);
        return Combinators.combine(state, percentage)
                .as((s, p) -> new AreaContribution("area", "name.key", p, s, List.of()));
    }

    /** Lists of 0..6 contributions (6 = the number of design-stage areas). */
    @Provide
    Arbitrary<List<AreaContribution>> contributions() {
        return contribution().list().ofMinSize(0).ofMaxSize(6);
    }

    /** Non-empty lists of 1..6 contributions, for the permutation-invariance properties. */
    @Provide
    Arbitrary<List<AreaContribution>> nonEmptyContributions() {
        return contribution().list().ofMinSize(1).ofMaxSize(6);
    }

    private static List<AreaContribution> reversed(List<AreaContribution> input) {
        List<AreaContribution> copy = new ArrayList<>(input);
        java.util.Collections.reverse(copy);
        return copy;
    }
}
