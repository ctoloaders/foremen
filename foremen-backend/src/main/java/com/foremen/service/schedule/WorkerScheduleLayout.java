package com.foremen.service.schedule;

import com.foremen.exception.ForemenApiException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure auto-layout of a project's per-worker planning Gantt (FOR-05-10b Requirements 4.1, 4.2, 4.3,
 * 4.4; design §"Coverage and auto-layout → WorkerScheduleLayout.autoLayout"). Given the ordered
 * work categories (each with its already-computed half-day workload) and the resolved
 * {@link CoverageResolver.Coverage}, it produces the full set of {@code Worker_Bar}s on the half-day
 * grid.
 *
 * <p>This is a <strong>pure</strong> helper in the same vein as {@link ScheduleCalculator},
 * {@link ScheduleRowDerivation}, and {@link CoverageResolver}: every method is static and
 * side-effect free — no database, no Spring context, no clock — and the result depends only on the
 * arguments, so each layout rule is directly unit- and property-testable (task 2.3). It works on
 * plain ids (member ids, category ids) rather than JPA entities, so the service owns the entity →
 * id mapping and the per-category workload computation (via {@link ScheduleCalculator}), and the
 * layout stays trivially testable.
 *
 * <p>The algorithm (design §"WorkerScheduleLayout.autoLayout"), walking the categories in the
 * supplied {@code (WorkCategory.orderNo, id)} order:
 *
 * <ol>
 *   <li><b>Equal parallel split (D-split, R4.1).</b> For a {@code Covered_Category} with {@code n}
 *       covering workers each covering worker gets a bar of
 *       {@code perWorkerHalfDays = max(1, ceil(categoryHalfDays / n))} half-days; all of a
 *       category's bars share the category's provisional start, so they run in parallel.</li>
 *   <li><b>Uncovered virtual bar (D-uncovered, R4.2).</b> An {@code Uncovered_Category} gets exactly
 *       one bar with a {@code null} member of the full {@code categoryHalfDays} length.</li>
 *   <li><b>Per-worker sequential layout + same-worker back-to-back (D-layout, R4.3).</b> Each worker
 *       keeps a cursor at the half-day after their last bar's finish; their next bar starts at
 *       {@code max(cursor, categoryProvisionalStart)}. Two bars of the <em>same</em> worker in
 *       consecutive categories are therefore placed back to back with no gap and no overlap.</li>
 *   <li><b>One-day disjoint-neighbor overlap (R-overlap, R4.4).</b> When the current category's
 *       worker set is disjoint from the immediately preceding category's worker set (different
 *       people), the current category's provisional start is pulled back by up to {@code 2}
 *       half-days (1 day) toward the predecessor's last finish, never below half-day {@code 1} and
 *       never pulling a <em>same</em> worker's bar before their own cursor.</li>
 *   <li><b>Horizon reject (R4.4, R3.4).</b> If any bar's half-day finish exceeds
 *       {@value ScheduleCalculator#MAX_HALF_DAY} the whole layout is rejected with
 *       {@code error.schedule.too.long}.</li>
 * </ol>
 *
 * <p>The result is deterministic: identical inputs yield identical bars in a stable order
 * (categories in input order; within a category, covering workers in ascending member-id order as
 * resolved by {@link CoverageResolver}).
 */
public final class WorkerScheduleLayout {

    /** The overlap, in half-days, allowed between two disjoint-worker neighboring categories (1 day). */
    public static final int NEIGHBOR_OVERLAP_HALF_DAYS = 2;

    private WorkerScheduleLayout() {
    }

    /**
     * One category's workload for auto-layout: its id and the whole number of half-days of work it
     * carries ({@code categoryHalfDays}, {@code >= 1}; the caller computes it from the category's
     * net value and the {@code Daily_Output_Rate} via {@link ScheduleCalculator}). Categories must
     * be supplied in {@code (WorkCategory.orderNo, id)} order (R4.3).
     *
     * @param workCategoryId   the work category (never {@code null})
     * @param categoryHalfDays the category's total workload in half-days ({@code >= 1})
     */
    public record CategoryWork(Long workCategoryId, int categoryHalfDays) {

        public CategoryWork {
            Objects.requireNonNull(workCategoryId, "workCategoryId must not be null");
            if (categoryHalfDays < 1) {
                throw new IllegalArgumentException(
                        "categoryHalfDays must be >= 1, was " + categoryHalfDays);
            }
        }
    }

    /**
     * One auto-laid-out {@code Worker_Bar}: the worker it belongs to (or {@code null} for the
     * {@code Uncovered_Category} virtual bar), the category it plans, and its half-day period. All
     * four values map straight onto a {@code ProjectScheduleBarEntity}
     * ({@code project_member_id}, {@code work_category_id}, {@code start_half_days},
     * {@code duration_half_days}).
     *
     * @param projectMemberId  the covering worker's member id, or {@code null} for a virtual
     *                         uncovered bar (D-uncovered)
     * @param workCategoryId   the work category the bar plans (never {@code null})
     * @param startHalfDays    the 1-based start of the bar in half-days ({@code >= 1})
     * @param durationHalfDays the bar's duration in half-days ({@code >= 1})
     */
    public record WorkerBarLayout(
            Long projectMemberId, Long workCategoryId, int startHalfDays, int durationHalfDays) {

        /** The inclusive half-day finish offset of this bar: {@code start + duration - 1}. */
        public int finishHalfDay() {
            return ScheduleCalculator.finishHalfDay(startHalfDays, durationHalfDays);
        }
    }

    /**
     * The full auto-layout result (design §"WorkerScheduleLayout.autoLayout" output): the laid-out
     * {@code Worker_Bar}s plus the auto-create alert data — the {@code Uncovered_Category} ids and
     * whether the crew was empty.
     *
     * @param bars                 the laid-out bars, categories in input order and covering workers
     *                             in ascending member-id order within a category; an immutable
     *                             snapshot
     * @param uncoveredCategoryIds the ids of the categories that had no covering worker, in input
     *                             order; an immutable snapshot (R4.2, R4.5)
     * @param crewEmpty            {@code true} iff there was no covering worker for any category —
     *                             i.e. an empty crew (R4.6)
     */
    public record LayoutResult(
            List<WorkerBarLayout> bars, List<Long> uncoveredCategoryIds, boolean crewEmpty) {

        public LayoutResult {
            bars = List.copyOf(bars);
            uncoveredCategoryIds = List.copyOf(uncoveredCategoryIds);
        }
    }

    /**
     * Lays every category out into {@code Worker_Bar}s per the rules above.
     *
     * @param categories the categories to lay out, each with its half-day workload, already in
     *                   {@code (WorkCategory.orderNo, id)} order (R4.3); may be empty (yields an
     *                   empty layout). Must not contain {@code null} entries
     * @param coverage   the resolved coverage of those categories by the ACTIVE WORKER members
     *                   (from {@link CoverageResolver#resolve}); a category not present in the
     *                   coverage is treated as uncovered
     * @return the {@link LayoutResult}: the bars plus the uncovered-category / empty-crew alert data
     * @throws ForemenApiException      HTTP 409 {@code error.schedule.too.long} when any bar's finish
     *                                  would exceed {@value ScheduleCalculator#MAX_HALF_DAY} (R4.4)
     * @throws NullPointerException     if {@code categories} or {@code coverage} is {@code null}
     */
    public static LayoutResult autoLayout(
            List<CategoryWork> categories, CoverageResolver.Coverage coverage) {
        Objects.requireNonNull(categories, "categories must not be null");
        Objects.requireNonNull(coverage, "coverage must not be null");

        List<WorkerBarLayout> bars = new ArrayList<>();
        List<Long> uncovered = new ArrayList<>();

        // Each worker's cursor: the next free half-day after their last laid-out bar's finish.
        // Keyed by projectMemberId; the virtual uncovered "worker" of each category is tracked
        // under its own sentinel key so same-virtual-category disjointness is computed correctly.
        Map<Object, Integer> workerCursor = new LinkedHashMap<>();

        // The immediately preceding category's worker set and the latest half-day any of its bars
        // finished at. Null worker set / 0 finish mean "no predecessor yet" (first category).
        Set<Object> predecessorWorkers = null;
        int predecessorLastFinish = 0;
        boolean anyCovered = false;

        for (CategoryWork category : categories) {
            Objects.requireNonNull(category, "categories must not contain null entries");
            Long categoryId = category.workCategoryId();
            List<Long> coveringWorkers = coverage.coveringWorkers(categoryId);

            // The current category's worker set, used both to lay the bars out and to decide the
            // neighbor overlap against the predecessor.
            Set<Object> currentWorkers = new LinkedHashSet<>();
            boolean coveredCategory = !coveringWorkers.isEmpty();
            if (coveredCategory) {
                anyCovered = true;
                currentWorkers.addAll(coveringWorkers);
            } else {
                uncovered.add(categoryId);
                // A distinct virtual worker per uncovered category (D-uncovered).
                currentWorkers.add(new VirtualWorker(categoryId));
            }

            // Neighbor overlap (R4.4): pull the provisional start back by up to 2 half-days when the
            // current and preceding worker sets are disjoint (different people), never below 1.
            int overlap = (predecessorWorkers != null
                    && Collections.disjoint(predecessorWorkers, currentWorkers))
                    ? NEIGHBOR_OVERLAP_HALF_DAYS : 0;
            int categoryProvisionalStart = predecessorWorkers == null
                    ? 1
                    : Math.max(1, predecessorLastFinish + 1 - overlap);

            int categoryLastFinish = 0;
            if (coveredCategory) {
                int perWorkerHalfDays = perWorkerHalfDays(category.categoryHalfDays(),
                        coveringWorkers.size());
                for (Long memberId : coveringWorkers) {
                    categoryLastFinish = Math.max(categoryLastFinish, placeBar(
                            bars, workerCursor, memberId, memberId, categoryId,
                            categoryProvisionalStart, perWorkerHalfDays));
                }
            } else {
                // Single virtual bar of the full category workload (D-uncovered, R4.2).
                Object virtualKey = currentWorkers.iterator().next();
                categoryLastFinish = placeBar(
                        bars, workerCursor, virtualKey, null, categoryId,
                        categoryProvisionalStart, category.categoryHalfDays());
            }

            predecessorWorkers = currentWorkers;
            predecessorLastFinish = categoryLastFinish;
        }

        boolean crewEmpty = !anyCovered;
        return new LayoutResult(bars, uncovered, crewEmpty);
    }

    /**
     * Places one bar for a worker: starts it at {@code max(workerCursor, categoryProvisionalStart)}
     * (so a worker's own bars stay sequential and back-to-back, R4.3), appends it, advances the
     * worker's cursor past its finish, and rejects the layout if the finish overruns the horizon.
     *
     * @return the bar's inclusive half-day finish offset
     */
    private static int placeBar(
            List<WorkerBarLayout> bars, Map<Object, Integer> workerCursor,
            Object cursorKey, Long memberId, Long categoryId,
            int categoryProvisionalStart, int durationHalfDays) {

        int cursor = workerCursor.getOrDefault(cursorKey, 1);
        int start = Math.max(cursor, categoryProvisionalStart);
        int finish = ScheduleCalculator.finishHalfDay(start, durationHalfDays);
        if (finish > ScheduleCalculator.MAX_HALF_DAY) {
            throw new ForemenApiException(HttpStatus.CONFLICT, "error.schedule.too.long");
        }
        bars.add(new WorkerBarLayout(memberId, categoryId, start, durationHalfDays));
        workerCursor.put(cursorKey, finish + 1);
        return finish;
    }

    /**
     * The equal parallel split (D-split, R4.1):
     * {@code perWorkerHalfDays = max(1, ceil(categoryHalfDays / coveringWorkerCount))}. Integer
     * ceiling division via {@code (a + b - 1) / b}; a minimum of one half-day guarantees every
     * covering worker gets a visible bar.
     *
     * @param categoryHalfDays      the category's total workload in half-days ({@code >= 1})
     * @param coveringWorkerCount   the number of covering workers ({@code >= 1})
     * @return the per-worker half-day length, at least 1
     */
    public static int perWorkerHalfDays(int categoryHalfDays, int coveringWorkerCount) {
        if (coveringWorkerCount < 1) {
            throw new IllegalArgumentException(
                    "coveringWorkerCount must be >= 1, was " + coveringWorkerCount);
        }
        int ceil = (categoryHalfDays + coveringWorkerCount - 1) / coveringWorkerCount;
        return Math.max(1, ceil);
    }

    /**
     * A distinct virtual "worker" standing in for an {@code Uncovered_Category}'s single virtual bar
     * (D-uncovered). Two different uncovered categories get two different virtual workers, so the
     * neighbor-overlap disjointness check treats them as different people; a category compared with
     * itself (not something that happens here) would be the same instance.
     */
    private record VirtualWorker(Long categoryId) {
    }
}
