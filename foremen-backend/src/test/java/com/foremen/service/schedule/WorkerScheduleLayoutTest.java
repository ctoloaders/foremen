package com.foremen.service.schedule;

import com.foremen.exception.ForemenApiException;
import com.foremen.service.schedule.CoverageResolver.Coverage;
import com.foremen.service.schedule.CoverageResolver.WorkerMember;
import com.foremen.service.schedule.WorkerScheduleLayout.CategoryWork;
import com.foremen.service.schedule.WorkerScheduleLayout.LayoutResult;
import com.foremen.service.schedule.WorkerScheduleLayout.WorkerBarLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Example-based unit tests for the FOR-05-10b {@link WorkerScheduleLayout}: concrete auto-layout
 * decisions a regression would break — the equal parallel split, per-worker sequential layout,
 * same-worker back-to-back placement, the one-day disjoint-neighbor overlap, the uncovered virtual
 * bar, and the horizon reject (design §"Coverage and auto-layout → WorkerScheduleLayout.autoLayout").
 * The universal rules are covered by {@link WorkerScheduleLayoutPropertyTest}.
 *
 * <p><b>Validates: Requirements 4.1, 4.2, 4.3, 4.4</b>
 */
class WorkerScheduleLayoutTest {

    private static CategoryWork cat(long id, int halfDays) {
        return new CategoryWork(id, halfDays);
    }

    private static WorkerMember member(long id, Long... categories) {
        return new WorkerMember(id, Set.of(categories));
    }

    private static Coverage coverage(List<Long> categoryIds, WorkerMember... members) {
        return CoverageResolver.resolve(categoryIds, List.of(members));
    }

    private static WorkerBarLayout barFor(LayoutResult result, Long memberId, Long categoryId) {
        return result.bars().stream()
                .filter(b -> java.util.Objects.equals(b.projectMemberId(), memberId)
                        && b.workCategoryId().equals(categoryId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no bar for member " + memberId + " category " + categoryId));
    }

    @Nested
    @DisplayName("equal parallel split (D-split, R4.1)")
    class EqualParallelSplit {

        @Test
        @DisplayName("two covering workers split a category evenly, in parallel on the same start")
        void twoWorkersSplitEvenlyInParallel() {
            // Category 10 has 10 half-days of work covered by workers 1 and 2 → 5 half-days each.
            LayoutResult result = WorkerScheduleLayout.autoLayout(
                    List.of(cat(10L, 10)),
                    coverage(List.of(10L), member(1L, 10L), member(2L, 10L)));

            WorkerBarLayout barOne = barFor(result, 1L, 10L);
            WorkerBarLayout barTwo = barFor(result, 2L, 10L);

            assertThat(barOne.durationHalfDays()).isEqualTo(5);
            assertThat(barTwo.durationHalfDays()).isEqualTo(5);
            // Parallel: both start at the first half-day.
            assertThat(barOne.startHalfDays()).isEqualTo(1);
            assertThat(barTwo.startHalfDays()).isEqualTo(1);
        }

        @Test
        @DisplayName("an odd workload is split by ceiling division")
        void oddWorkloadSplitByCeiling() {
            // 11 half-days over 2 workers → ceil(11/2) = 6 each.
            LayoutResult result = WorkerScheduleLayout.autoLayout(
                    List.of(cat(10L, 11)),
                    coverage(List.of(10L), member(1L, 10L), member(2L, 10L)));

            assertThat(barFor(result, 1L, 10L).durationHalfDays()).isEqualTo(6);
            assertThat(barFor(result, 2L, 10L).durationHalfDays()).isEqualTo(6);
        }

        @Test
        @DisplayName("more workers than half-days still gives each a minimum one-half-day bar")
        void minimumOneHalfDayPerWorker() {
            // 1 half-day over 3 workers → ceil(1/3) = 1, floored at 1.
            LayoutResult result = WorkerScheduleLayout.autoLayout(
                    List.of(cat(10L, 1)),
                    coverage(List.of(10L), member(1L, 10L), member(2L, 10L), member(3L, 10L)));

            assertThat(result.bars()).hasSize(3);
            assertThat(result.bars()).allSatisfy(bar ->
                    assertThat(bar.durationHalfDays()).isEqualTo(1));
        }
    }

    @Nested
    @DisplayName("per-worker sequential layout + same-worker back-to-back (D-layout, R4.3)")
    class SequentialLayout {

        @Test
        @DisplayName("one worker covering two categories gets back-to-back bars, no gap")
        void sameWorkerBackToBack() {
            // Worker 1 covers categories 10 (4 hd) and 20 (6 hd). Bar on 10 is days [1..4],
            // bar on 20 starts at 5 (back to back, no gap).
            LayoutResult result = WorkerScheduleLayout.autoLayout(
                    List.of(cat(10L, 4), cat(20L, 6)),
                    coverage(List.of(10L, 20L), member(1L, 10L, 20L)));

            WorkerBarLayout first = barFor(result, 1L, 10L);
            WorkerBarLayout second = barFor(result, 1L, 20L);

            assertThat(first.startHalfDays()).isEqualTo(1);
            assertThat(first.finishHalfDay()).isEqualTo(4);
            // Back-to-back: next starts exactly at previous finish + 1, no overlap (same worker).
            assertThat(second.startHalfDays()).isEqualTo(5);
            assertThat(second.finishHalfDay()).isEqualTo(10);
        }

        @Test
        @DisplayName("other workers' timelines are independent")
        void independentTimelines() {
            // Category 10 (4 hd) covered by worker 1; category 20 (6 hd) by worker 2. The two are
            // different workers (disjoint), so category 20 may overlap by up to one day (2 hd):
            // predecessor finish 4, provisional start max(1, 4+1-2) = 3.
            LayoutResult result = WorkerScheduleLayout.autoLayout(
                    List.of(cat(10L, 4), cat(20L, 6)),
                    coverage(List.of(10L, 20L), member(1L, 10L), member(2L, 20L)));

            assertThat(barFor(result, 1L, 10L).startHalfDays()).isEqualTo(1);
            assertThat(barFor(result, 2L, 20L).startHalfDays()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("one-day disjoint-neighbor overlap (R-overlap, R4.4)")
    class NeighborOverlap {

        @Test
        @DisplayName("disjoint neighbors overlap by one day (2 half-days)")
        void disjointNeighborsOverlapOneDay() {
            // Cat 10 (6 hd) by worker 1 → [1..6]; cat 20 (4 hd) by worker 2, disjoint, pulled back
            // 2 half-days → start max(1, 6+1-2) = 5.
            LayoutResult result = WorkerScheduleLayout.autoLayout(
                    List.of(cat(10L, 6), cat(20L, 4)),
                    coverage(List.of(10L, 20L), member(1L, 10L), member(2L, 20L)));

            assertThat(barFor(result, 2L, 20L).startHalfDays()).isEqualTo(5);
        }

        @Test
        @DisplayName("overlap never pulls a start below one half-day")
        void overlapNeverBelowOne() {
            // Cat 10 (1 hd) by worker 1 → finish 1; cat 20 (3 hd) by worker 2, disjoint. Pull-back
            // would be max(1, 1+1-2) = max(1, 0) = 1 — clamped to 1.
            LayoutResult result = WorkerScheduleLayout.autoLayout(
                    List.of(cat(10L, 1), cat(20L, 3)),
                    coverage(List.of(10L, 20L), member(1L, 10L), member(2L, 20L)));

            assertThat(barFor(result, 2L, 20L).startHalfDays()).isEqualTo(1);
        }

        @Test
        @DisplayName("neighbors sharing a worker do not overlap")
        void sharedWorkerNoOverlap() {
            // Cat 10 (4 hd) and cat 20 (4 hd) both covered by worker 1 (shared). No overlap: the
            // worker's own cursor forces back-to-back [1..4] then [5..8].
            LayoutResult result = WorkerScheduleLayout.autoLayout(
                    List.of(cat(10L, 4), cat(20L, 4)),
                    coverage(List.of(10L, 20L), member(1L, 10L, 20L)));

            assertThat(barFor(result, 1L, 10L).finishHalfDay()).isEqualTo(4);
            assertThat(barFor(result, 1L, 20L).startHalfDays()).isEqualTo(5);
        }

        @Test
        @DisplayName("a worker shared across non-adjacent categories still never overlaps itself")
        void sharedWorkerCursorWins() {
            // Worker 1 covers cat 10 (6 hd) and cat 30 (4 hd); worker 2 covers cat 20 (2 hd).
            // Layout order 10, 20, 30. 10 → worker1 [1..6]. 20 → worker2, disjoint from 10,
            // provisional start max(1, 6+1-2)=5, bar [5..6]. 30 → worker1, disjoint from 20
            // (different worker), provisional start max(1, 6+1-2)=5, but worker1's cursor is 7, so
            // its own bar wins → starts at 7, no self-overlap.
            LayoutResult result = WorkerScheduleLayout.autoLayout(
                    List.of(cat(10L, 6), cat(20L, 2), cat(30L, 4)),
                    coverage(List.of(10L, 20L, 30L),
                            member(1L, 10L, 30L), member(2L, 20L)));

            assertThat(barFor(result, 1L, 10L).finishHalfDay()).isEqualTo(6);
            assertThat(barFor(result, 2L, 20L).startHalfDays()).isEqualTo(5);
            assertThat(barFor(result, 1L, 30L).startHalfDays()).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("uncovered virtual bar (D-uncovered, R4.2)")
    class UncoveredVirtualBar {

        @Test
        @DisplayName("an uncovered category gets one full-length null-member bar and an alert id")
        void uncoveredFullLengthVirtualBar() {
            LayoutResult result = WorkerScheduleLayout.autoLayout(
                    List.of(cat(99L, 8)),
                    coverage(List.of(99L))); // no members → uncovered

            assertThat(result.bars()).hasSize(1);
            WorkerBarLayout virtual = result.bars().get(0);
            assertThat(virtual.projectMemberId()).isNull();
            assertThat(virtual.workCategoryId()).isEqualTo(99L);
            assertThat(virtual.durationHalfDays()).isEqualTo(8);
            assertThat(virtual.startHalfDays()).isEqualTo(1);

            assertThat(result.uncoveredCategoryIds()).containsExactly(99L);
            assertThat(result.crewEmpty()).isTrue();
        }

        @Test
        @DisplayName("empty crew lays every category out as a virtual bar and flags crewEmpty")
        void emptyCrewAllVirtual() {
            LayoutResult result = WorkerScheduleLayout.autoLayout(
                    List.of(cat(10L, 4), cat(20L, 6)),
                    coverage(List.of(10L, 20L))); // no members

            assertThat(result.bars()).allSatisfy(bar ->
                    assertThat(bar.projectMemberId()).isNull());
            assertThat(result.uncoveredCategoryIds()).containsExactly(10L, 20L);
            assertThat(result.crewEmpty()).isTrue();
        }

        @Test
        @DisplayName("two uncovered neighbors are back-to-back (distinct virtual workers, no overlap)")
        void twoUncoveredNeighborsBackToBack() {
            // Both uncovered: distinct virtual workers, so they are "disjoint" and the overlap rule
            // would pull back — but each virtual worker's own cursor keeps them sequential. Cat 10
            // [1..4], cat 20 provisional start max(1, 4+1-2)=3, its own virtual cursor is 1, so it
            // starts at max(1, 3) = 3.
            LayoutResult result = WorkerScheduleLayout.autoLayout(
                    List.of(cat(10L, 4), cat(20L, 6)),
                    coverage(List.of(10L, 20L)));

            WorkerBarLayout first = result.bars().get(0);
            WorkerBarLayout second = result.bars().get(1);
            assertThat(first.workCategoryId()).isEqualTo(10L);
            assertThat(first.finishHalfDay()).isEqualTo(4);
            assertThat(second.workCategoryId()).isEqualTo(20L);
            assertThat(second.startHalfDays()).isEqualTo(3);
        }

        @Test
        @DisplayName("a mix of covered and uncovered categories flags only the uncovered ones")
        void mixedCoverage() {
            LayoutResult result = WorkerScheduleLayout.autoLayout(
                    List.of(cat(10L, 4), cat(20L, 6)),
                    coverage(List.of(10L, 20L), member(1L, 10L))); // only 10 covered

            assertThat(result.uncoveredCategoryIds()).containsExactly(20L);
            assertThat(result.crewEmpty()).isFalse();
            assertThat(barFor(result, 1L, 10L)).isNotNull();
            assertThat(barFor(result, null, 20L).projectMemberId()).isNull();
        }
    }

    @Nested
    @DisplayName("horizon reject (R4.4)")
    class HorizonReject {

        @Test
        @DisplayName("a single bar finishing beyond 7300 half-days is rejected")
        void singleBarBeyondHorizon() {
            int tooBig = ScheduleCalculator.MAX_HALF_DAY + 1; // finish = 7301
            assertThatThrownBy(() -> WorkerScheduleLayout.autoLayout(
                    List.of(cat(1L, tooBig)),
                    coverage(List.of(1L))))
                    .isInstanceOf(ForemenApiException.class)
                    .hasMessage("error.schedule.too.long");
        }

        @Test
        @DisplayName("a bar whose finish is exactly 7300 is accepted")
        void exactlyHorizonAccepted() {
            int exact = ScheduleCalculator.MAX_HALF_DAY; // finish = start(1)+dur-1 = 7300
            LayoutResult result = WorkerScheduleLayout.autoLayout(
                    List.of(cat(1L, exact)),
                    coverage(List.of(1L)));

            assertThat(result.bars()).hasSize(1);
            assertThat(result.bars().get(0).finishHalfDay())
                    .isEqualTo(ScheduleCalculator.MAX_HALF_DAY);
        }

        @Test
        @DisplayName("a sequential chain for one worker that overruns the horizon is rejected")
        void sequentialChainOverrun() {
            int half = ScheduleCalculator.MAX_HALF_DAY; // 7300
            // Worker 1 covers both: [1..7300] then would start at 7301 → overrun.
            assertThatThrownBy(() -> WorkerScheduleLayout.autoLayout(
                    List.of(cat(10L, half), cat(20L, 2)),
                    coverage(List.of(10L, 20L), member(1L, 10L, 20L))))
                    .isInstanceOf(ForemenApiException.class)
                    .hasMessage("error.schedule.too.long");
        }
    }

    @Nested
    @DisplayName("degenerate inputs")
    class Degenerate {

        @Test
        @DisplayName("no categories yields an empty layout and a (vacuously) empty crew")
        void noCategories() {
            LayoutResult result = WorkerScheduleLayout.autoLayout(
                    List.of(), coverage(List.of(), member(1L, 10L)));

            assertThat(result.bars()).isEmpty();
            assertThat(result.uncoveredCategoryIds()).isEmpty();
            assertThat(result.crewEmpty()).isTrue();
        }

        @Test
        @DisplayName("the result collections are immutable snapshots")
        void immutableResult() {
            LayoutResult result = WorkerScheduleLayout.autoLayout(
                    List.of(cat(10L, 4)), coverage(List.of(10L), member(1L, 10L)));

            assertThatThrownBy(() -> result.bars().add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> result.uncoveredCategoryIds().add(1L))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a null categories list is rejected")
        void nullCategoriesRejected() {
            assertThatThrownBy(() -> WorkerScheduleLayout.autoLayout(null, coverage(List.of())))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("CategoryWork rejects a non-positive workload and a null id")
        void categoryWorkValidation() {
            assertThatThrownBy(() -> new CategoryWork(1L, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new CategoryWork(null, 4))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
