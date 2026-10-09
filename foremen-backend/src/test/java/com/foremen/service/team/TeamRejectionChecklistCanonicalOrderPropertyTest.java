package com.foremen.service.team;

import com.foremen.exception.ForemenApiException;
import com.foremen.service.team.TeamRejectionChecklist.Builder;
import com.foremen.service.team.TeamRejectionChecklist.Step;
import com.foremen.service.team.TeamRejectionChecklist.TeamCheck;
import com.foremen.service.team.TeamRejectionChecklist.TeamRejectionContext;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based test for {@link TeamRejectionChecklist} (FOR-05-09 task 6.2, Requirements 3.7,
 * 10.4).
 *
 * <p><b>Property 11: The first failing check in the canonical order determines the response.</b>
 * For any mutating Team_API request that trips an arbitrary subset of the canonical checks, the
 * checklist propagates the error of the <em>first</em> tripped check in the fixed canonical {@link
 * Step} order and of no later check, and no check after the first failure ever runs. When no check
 * trips, {@link TeamRejectionChecklist#run(TeamRejectionContext)} returns normally.
 *
 * <p>The checklist is a pure sequencer (no Spring, no DB), so this test drives it directly with
 * synthetic {@link TeamCheck}s. Each step is wired with a check that:
 * <ul>
 *   <li>records that it executed (into a shared {@code executed} list, in execution order), and</li>
 *   <li>if that step is in the generated failing subset, throws a {@link ForemenApiException} whose
 *       {@code messageCode} is a <em>distinct marker</em> identifying exactly which step tripped
 *       ({@code "marker.<STEP_NAME>"}) and whose status encodes the ordinal — so the propagated
 *       error unambiguously names its originating step.</li>
 * </ul>
 *
 * <p>Because the generated failing subset and the builder-registration order are both arbitrary,
 * the test proves the precedence is governed by the canonical {@link Step} ordinal and not by the
 * order the builder set the checks in.
 *
 * <p>Feature: FOR-05-09-team-selection, Property 11
 *
 * <p><b>Validates: Requirements 3.7, 10.4</b>
 */
// Feature: FOR-05-09-team-selection, Property 11: The first failing check in the canonical order determines the response
@Tag("Feature: FOR-05-09-team-selection, Property 11")
class TeamRejectionChecklistCanonicalOrderPropertyTest {

    private static final String MARKER_PREFIX = "marker.";

    /** The distinct marker message code a failing check for {@code step} throws. */
    private static String markerFor(Step step) {
        return MARKER_PREFIX + step.name();
    }

    // ------------------------------------------------------------------------------------------
    // Property 11a: the first tripped check (earliest by canonical Step ordinal) determines the
    // propagated error, no later check runs, and a clean run returns normally.
    // Validates: Requirements 3.7, 10.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 300)
    @Tag("Feature: FOR-05-09-team-selection, Property 11")
    void firstFailingStepInCanonicalOrderWinsAndNoLaterCheckRuns(
            @ForAll("failingSubsets") Set<Step> failing,
            @ForAll("registrationOrders") List<Step> registrationOrder) {

        // The checks that actually ran, in execution order (shared side-effect probe).
        List<Step> executed = new ArrayList<>();

        // Register every step's check, but in the arbitrary registration order, to prove the
        // canonical precedence does not depend on builder ordering.
        Builder builder = TeamRejectionContext.builder();
        for (Step step : registrationOrder) {
            builder.at(step, recordingCheck(step, failing, executed));
        }
        TeamRejectionContext context = builder.build();

        if (failing.isEmpty()) {
            // No step fails => run returns normally and every registered check ran, in canonical order.
            assertThatCode(() -> TeamRejectionChecklist.run(context)).doesNotThrowAnyException();
            assertThat(executed).containsExactlyElementsOf(canonicalOrder());
            return;
        }

        // The expected winner is the earliest failing step by canonical Step ordinal.
        Step expectedWinner = earliestByOrdinal(failing);

        assertThatThrownBy(() -> TeamRejectionChecklist.run(context))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    // The propagated error is exactly the first tripped step's marker...
                    assertThat(ex.getMessageCode()).isEqualTo(markerFor(expectedWinner));
                    // ...and its status encodes that same step's ordinal (no later check's).
                    assertThat(ex.getStatus().value()).isEqualTo(statusCodeFor(expectedWinner));
                });

        // Every check strictly before the winner ran, in canonical order; the winner ran (and threw);
        // no step after the winner ran — proving the sequence stopped at the first failure.
        List<Step> expectedExecuted = canonicalOrder().stream()
                .filter(s -> s.ordinal() <= expectedWinner.ordinal())
                .toList();
        assertThat(executed).containsExactlyElementsOf(expectedExecuted);
        assertThat(executed).noneMatch(s -> s.ordinal() > expectedWinner.ordinal());
    }

    // ------------------------------------------------------------------------------------------
    // Property 11b: edge case — a single failing step always wins, regardless of its position, and
    // exactly the steps up to and including it run.
    // Validates: Requirements 3.7, 10.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-09-team-selection, Property 11")
    void aSingleFailingStepIsAlwaysTheWinner(@ForAll("anyStep") Step failingStep) {
        List<Step> executed = new ArrayList<>();
        Set<Step> failing = EnumSet.of(failingStep);

        Builder builder = TeamRejectionContext.builder();
        // Register in reverse canonical order to further decouple from registration order.
        List<Step> reversed = new ArrayList<>(canonicalOrder());
        Collections.reverse(reversed);
        for (Step step : reversed) {
            builder.at(step, recordingCheck(step, failing, executed));
        }
        TeamRejectionContext context = builder.build();

        assertThatThrownBy(() -> TeamRejectionChecklist.run(context))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo(markerFor(failingStep)));

        List<Step> expectedExecuted = canonicalOrder().stream()
                .filter(s -> s.ordinal() <= failingStep.ordinal())
                .toList();
        assertThat(executed).containsExactlyElementsOf(expectedExecuted);
    }

    // ------------------------------------------------------------------------------------------
    // Property 11c: edge case — null (unset / not-applicable) steps are skipped no-ops and never
    // change which step wins. Only non-null failing checks count.
    // Validates: Requirements 3.7, 10.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-09-team-selection, Property 11")
    void unsetStepsAreSkippedAndDoNotAffectThePrecedence(
            @ForAll("failingSubsets") Set<Step> failing,
            @ForAll("subsetOfSteps") Set<Step> registeredSteps) {

        // Only steps that are BOTH registered AND in the failing set can actually trip.
        Set<Step> effectiveFailing = EnumSet.noneOf(Step.class);
        effectiveFailing.addAll(failing);
        effectiveFailing.retainAll(registeredSteps);

        List<Step> executed = new ArrayList<>();
        Builder builder = TeamRejectionContext.builder();
        // Register only the chosen subset; every other step stays an unset no-op (null).
        for (Step step : registeredSteps) {
            builder.at(step, recordingCheck(step, failing, executed));
        }
        TeamRejectionContext context = builder.build();

        if (effectiveFailing.isEmpty()) {
            assertThatCode(() -> TeamRejectionChecklist.run(context)).doesNotThrowAnyException();
            // Every registered (non-null) check ran, in canonical order; unset steps contributed nothing.
            List<Step> expectedExecuted = canonicalOrder().stream()
                    .filter(registeredSteps::contains)
                    .toList();
            assertThat(executed).containsExactlyElementsOf(expectedExecuted);
            return;
        }

        Step expectedWinner = earliestByOrdinal(effectiveFailing);

        assertThatThrownBy(() -> TeamRejectionChecklist.run(context))
                .isInstanceOfSatisfying(ForemenApiException.class, ex ->
                        assertThat(ex.getMessageCode()).isEqualTo(markerFor(expectedWinner)));

        // Executed = registered steps whose ordinal <= winner's, in canonical order; nothing after.
        List<Step> expectedExecuted = canonicalOrder().stream()
                .filter(registeredSteps::contains)
                .filter(s -> s.ordinal() <= expectedWinner.ordinal())
                .toList();
        assertThat(executed).containsExactlyElementsOf(expectedExecuted);
        assertThat(executed).noneMatch(s -> s.ordinal() > expectedWinner.ordinal());
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /**
     * Builds a check for {@code step} that records its execution and, when {@code step} is in the
     * {@code failing} set, throws a distinct marker {@link ForemenApiException} naming the step.
     */
    private static TeamCheck recordingCheck(Step step, Set<Step> failing, List<Step> executed) {
        return () -> {
            executed.add(step);
            if (failing.contains(step)) {
                throw new ForemenApiException(
                        HttpStatus.valueOf(statusCodeFor(step)), markerFor(step));
            }
        };
    }

    /** The canonical ordering of all steps (declaration / ordinal order). */
    private static List<Step> canonicalOrder() {
        return List.of(Step.values());
    }

    /** The earliest step in {@code steps} by canonical {@link Step} ordinal. */
    private static Step earliestByOrdinal(Set<Step> steps) {
        return steps.stream().min((a, b) -> Integer.compare(a.ordinal(), b.ordinal())).orElseThrow();
    }

    /** A distinct, valid HTTP status code per step (encodes the ordinal: 400..406). */
    private static int statusCodeFor(Step step) {
        return 400 + step.ordinal();
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    @Provide
    Arbitrary<Step> anyStep() {
        return Arbitraries.of(Step.values());
    }

    /** An arbitrary subset of steps that are configured to fail (including the empty subset). */
    @Provide
    Arbitrary<Set<Step>> failingSubsets() {
        return Arbitraries.subsetOf(Step.values()).map(TeamRejectionChecklistCanonicalOrderPropertyTest::toEnumSet);
    }

    /** An arbitrary subset of steps to register (the rest stay unset no-ops). */
    @Provide
    Arbitrary<Set<Step>> subsetOfSteps() {
        return Arbitraries.subsetOf(Step.values()).map(TeamRejectionChecklistCanonicalOrderPropertyTest::toEnumSet);
    }

    /** Builds an {@link EnumSet} from any (possibly empty) collection of steps. */
    private static Set<Step> toEnumSet(Set<Step> steps) {
        Set<Step> result = EnumSet.noneOf(Step.class);
        result.addAll(steps);
        return result;
    }

    /** An arbitrary builder-registration order: a shuffled permutation of all steps. */
    @Provide
    Arbitrary<List<Step>> registrationOrders() {
        return Arbitraries.shuffle(List.of(Step.values()));
    }
}
