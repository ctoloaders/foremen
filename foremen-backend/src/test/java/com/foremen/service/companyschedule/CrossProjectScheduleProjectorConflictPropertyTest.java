package com.foremen.service.companyschedule;

import com.foremen.controller.model.companyschedule.BarKind;
import com.foremen.controller.model.companyschedule.ConflictPair;
import com.foremen.controller.model.companyschedule.ProjectedBar;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for the overlap / conflict detection of
 * {@link CrossProjectScheduleProjector#intervalsOverlap(LocalDate, LocalDate, LocalDate, LocalDate)}
 * and {@link CrossProjectScheduleProjector#detectConflicts(java.util.List)} (FOR-07-01, Requirement
 * 5; design §"Conflict detection", Property 7).
 *
 * <p>The projector helpers are pure, static functions over plain inputs — no persistence, no Spring
 * context, no clock — so these properties are cheap to run over 100+ iterations.
 *
 * <p>Property 7 (closed-interval overlap on worker bars only) decomposes into the checks below:
 *
 * <ul>
 *   <li><b>symmetry:</b> {@code intervalsOverlap(a, b) == intervalsOverlap(b, a)} for every pair of
 *       datable and undatable intervals.</li>
 *   <li><b>closed intervals:</b> intervals that merely touch at a shared endpoint overlap, and the
 *       helper agrees with the closed-interval reference rule
 *       {@code aStart <= bEnd && bStart <= aEnd}; any null bound means no overlap.</li>
 *   <li><b>worker bars only:</b> {@code detectConflicts} pairs only datable
 *       {@link BarKind#WORKER_CATEGORY} bars — a {@link BarKind#FOREMAN_SPAN} or an undatable bar is
 *       never part of a conflict.</li>
 *   <li><b>distinct indices into one member's list:</b> every {@link ConflictPair} holds
 *       {@code 0 <= barIndexA < barIndexB < bars.size()} and both referenced bars are datable
 *       worker bars whose closed intervals overlap — iff a pair is a conflict.</li>
 *   <li><b>determinism:</b> identical inputs yield identical, ascending-ordered conflict lists.</li>
 * </ul>
 *
 * <p>Feature: FOR-07-01-cross-project-schedule-core, Property 7: Conflict detection is symmetric
 * closed-interval overlap on worker bars only
 *
 * <p><b>Validates: Requirements 5.1, 5.2, 5.4, 5.5, 5.6</b>
 */
@Tag("Feature: FOR-07-01-cross-project-schedule-core, Property 7: Conflict detection is symmetric closed-interval overlap on worker bars only")
class CrossProjectScheduleProjectorConflictPropertyTest {

    // ------------------------------------------------------------------------------------------
    // Property 7a: intervalsOverlap is symmetric and uses closed intervals (R5.2, R5.4)
    //   It equals the reference closed-interval rule aStart <= bEnd && bStart <= aEnd, agrees under
    //   argument swap, and returns false whenever any of the four bounds is null (undatable).
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-07-01-cross-project-schedule-core, Property 7: Conflict detection is symmetric closed-interval overlap on worker bars only")
    void intervalsOverlapIsSymmetricClosedInterval(
            @ForAll("datableInterval") LocalDate[] a,
            @ForAll("datableInterval") LocalDate[] b) {

        boolean ab = CrossProjectScheduleProjector.intervalsOverlap(a[0], a[1], b[0], b[1]);
        boolean ba = CrossProjectScheduleProjector.intervalsOverlap(b[0], b[1], a[0], a[1]);

        // Symmetry: the order of the two intervals does not change the answer.
        assertThat(ab).isEqualTo(ba);

        // Closed-interval reference rule: aStart <= bEnd && bStart <= aEnd.
        boolean reference = !a[0].isAfter(b[1]) && !b[0].isAfter(a[1]);
        assertThat(ab).isEqualTo(reference);
    }

    // ------------------------------------------------------------------------------------------
    // Property 7b: shared endpoints count as an overlap (closed, not half-open) (R5.2)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-07-01-cross-project-schedule-core, Property 7: Conflict detection is symmetric closed-interval overlap on worker bars only")
    void touchingAtAnEndpointOverlaps(
            @ForAll("date") LocalDate start,
            @ForAll @IntRange(min = 0, max = 400) int aLen,
            @ForAll @IntRange(min = 0, max = 400) int bLen) {

        // a = [start, start + aLen] ; b = [start + aLen, start + aLen + bLen]
        // The two intervals share exactly the single day (start + aLen), so a closed-interval
        // overlap check MUST report an overlap (a half-open check would miss it).
        LocalDate aStart = start;
        LocalDate aEnd = start.plusDays(aLen);
        LocalDate bStart = aEnd;
        LocalDate bEnd = bStart.plusDays(bLen);

        assertThat(CrossProjectScheduleProjector.intervalsOverlap(aStart, aEnd, bStart, bEnd))
                .isTrue();
        assertThat(CrossProjectScheduleProjector.intervalsOverlap(bStart, bEnd, aStart, aEnd))
                .isTrue();
    }

    // ------------------------------------------------------------------------------------------
    // Property 7c: a null bound (undatable bar) never overlaps (R5.4)
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-07-01-cross-project-schedule-core, Property 7: Conflict detection is symmetric closed-interval overlap on worker bars only")
    void anyNullBoundNeverOverlaps(
            @ForAll("date") LocalDate x,
            @ForAll("date") LocalDate y,
            @ForAll("date") LocalDate z) {

        // Whatever the other three real bounds are, if any single bound is null the pair cannot
        // overlap: an Undatable_Bar has no absolute interval to compare.
        assertThat(CrossProjectScheduleProjector.intervalsOverlap(null, x, y, z)).isFalse();
        assertThat(CrossProjectScheduleProjector.intervalsOverlap(x, null, y, z)).isFalse();
        assertThat(CrossProjectScheduleProjector.intervalsOverlap(x, y, null, z)).isFalse();
        assertThat(CrossProjectScheduleProjector.intervalsOverlap(x, y, z, null)).isFalse();
    }

    // ------------------------------------------------------------------------------------------
    // Property 7d: detectConflicts pairs ONLY datable WORKER_CATEGORY bars, references two distinct
    //   ascending indices into the one member's bar list, matches intervalsOverlap exactly, and is
    //   deterministic (R5.1, R5.4, R5.5, R5.6, and the determinism clause of the property).
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-07-01-cross-project-schedule-core, Property 7: Conflict detection is symmetric closed-interval overlap on worker bars only")
    void detectConflictsPairsOnlyDatableWorkerBars(@ForAll("memberBars") List<ProjectedBar> bars) {

        List<ConflictPair> conflicts = CrossProjectScheduleProjector.detectConflicts(bars);

        // Determinism: a second call on the same input yields an identical list (R5 / R10.4).
        assertThat(CrossProjectScheduleProjector.detectConflicts(bars)).isEqualTo(conflicts);

        int previousA = -1;
        int previousB = -1;
        for (ConflictPair pair : conflicts) {
            int ia = pair.barIndexA();
            int ib = pair.barIndexB();

            // Two distinct indices into THIS member's bar list, A strictly before B (no self-pair).
            assertThat(ia).isGreaterThanOrEqualTo(0);
            assertThat(ib).isLessThan(bars.size());
            assertThat(ia).isLessThan(ib);

            ProjectedBar a = bars.get(ia);
            ProjectedBar b = bars.get(ib);

            // Both referenced bars are datable WORKER_CATEGORY bars — a FOREMAN_SPAN or an
            // undatable bar is never part of a conflict (R5.4, R5.5).
            assertThat(isDatableWorkerBar(a)).isTrue();
            assertThat(isDatableWorkerBar(b)).isTrue();

            // The reported conflict is exactly a closed-interval overlap of the two bars.
            assertThat(CrossProjectScheduleProjector.intervalsOverlap(
                    a.startDate(), a.endDate(), b.startDate(), b.endDate())).isTrue();

            // Ascending (barIndexA, barIndexB) ordering — deterministic output order (R10.4).
            assertThat(ia > previousA || (ia == previousA && ib > previousB)).isTrue();
            previousA = ia;
            previousB = ib;
        }

        // Completeness: every pair of datable worker bars that overlaps IS reported, and nothing
        // else is. This is the "if and only if" direction of the property.
        List<ConflictPair> expected = referenceConflicts(bars);
        assertThat(conflicts).isEqualTo(expected);
    }

    // ------------------------------------------------------------------------------------------
    // Reference implementation of the property statement, independent of the production loop.
    // ------------------------------------------------------------------------------------------

    private static List<ConflictPair> referenceConflicts(List<ProjectedBar> bars) {
        List<ConflictPair> expected = new ArrayList<>();
        for (int i = 0; i < bars.size(); i++) {
            for (int j = i + 1; j < bars.size(); j++) {
                ProjectedBar a = bars.get(i);
                ProjectedBar b = bars.get(j);
                if (isDatableWorkerBar(a) && isDatableWorkerBar(b)
                        && closedOverlap(a.startDate(), a.endDate(), b.startDate(), b.endDate())) {
                    expected.add(new ConflictPair(i, j));
                }
            }
        }
        return expected;
    }

    private static boolean isDatableWorkerBar(ProjectedBar bar) {
        return bar != null
                && bar.kind() == BarKind.WORKER_CATEGORY
                && bar.startDate() != null
                && bar.endDate() != null;
    }

    private static boolean closedOverlap(LocalDate aStart, LocalDate aEnd,
                                         LocalDate bStart, LocalDate bEnd) {
        return !aStart.isAfter(bEnd) && !bStart.isAfter(aEnd);
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /** A calendar date in a bounded window (keeps generated intervals in a realistic range). */
    @Provide
    Arbitrary<LocalDate> date() {
        return Arbitraries.longs()
                .between(0L, 400L)
                .map(offset -> LocalDate.of(2026, 1, 1).plusDays(offset));
    }

    /** A fully datable closed interval {@code [start, start + length]} with {@code start <= end}. */
    @Provide
    Arbitrary<LocalDate[]> datableInterval() {
        return Combinators.combine(date(), Arbitraries.integers().between(0, 400))
                .as((start, length) -> new LocalDate[] {start, start.plusDays(length)});
    }

    /**
     * One member's ordered {@code Projected_Bars}: a mix of datable worker bars (eligible for
     * conflicts), undatable worker bars (null dates), and foreman spans (datable and undatable).
     * All variants are generated so the property exercises the "worker bars only" and "undatable
     * never conflicts" clauses, not just the happy path.
     */
    @Provide
    Arbitrary<List<ProjectedBar>> memberBars() {
        return projectedBar().list().ofMinSize(0).ofMaxSize(12);
    }

    private Arbitrary<ProjectedBar> projectedBar() {
        Arbitrary<ProjectedBar> datableWorker = Combinators
                .combine(date(), Arbitraries.integers().between(0, 60), projectId())
                .as((start, length, projectId) -> workerBar(projectId, start, start.plusDays(length)));

        Arbitrary<ProjectedBar> undatableWorker = projectId()
                .map(projectId -> workerBar(projectId, null, null));

        Arbitrary<ProjectedBar> datableForeman = Combinators
                .combine(date(), Arbitraries.integers().between(0, 60), projectId())
                .as((start, length, projectId) -> foremanSpan(projectId, start, start.plusDays(length)));

        Arbitrary<ProjectedBar> undatableForeman = projectId()
                .map(projectId -> foremanSpan(projectId, null, null));

        // Weight datable worker bars higher so most lists contain real conflict candidates.
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(5, datableWorker),
                net.jqwik.api.Tuple.of(1, undatableWorker),
                net.jqwik.api.Tuple.of(2, datableForeman),
                net.jqwik.api.Tuple.of(1, undatableForeman));
    }

    private Arbitrary<Long> projectId() {
        return Arbitraries.longs().between(1L, 5L);
    }

    private static ProjectedBar workerBar(long projectId, LocalDate start, LocalDate end) {
        boolean unanchored = start == null || end == null;
        return new ProjectedBar(
                BarKind.WORKER_CATEGORY,
                projectId,
                "Project " + projectId,
                (int) (projectId % 8),
                100L + projectId,
                "Category " + projectId,
                1,
                2,
                start,
                end,
                unanchored);
    }

    private static ProjectedBar foremanSpan(long projectId, LocalDate start, LocalDate end) {
        boolean unanchored = start == null || end == null;
        return new ProjectedBar(
                BarKind.FOREMAN_SPAN,
                projectId,
                "Project " + projectId,
                (int) (projectId % 8),
                null,
                null,
                null,
                null,
                start,
                end,
                unanchored);
    }
}
