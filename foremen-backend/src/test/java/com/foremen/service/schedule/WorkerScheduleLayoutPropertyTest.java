package com.foremen.service.schedule;

import com.foremen.exception.ForemenApiException;
import com.foremen.service.schedule.CoverageResolver.Coverage;
import com.foremen.service.schedule.CoverageResolver.WorkerMember;
import com.foremen.service.schedule.WorkerScheduleLayout.CategoryWork;
import com.foremen.service.schedule.WorkerScheduleLayout.LayoutResult;
import com.foremen.service.schedule.WorkerScheduleLayout.WorkerBarLayout;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests for the FOR-05-10b {@link WorkerScheduleLayout}: the pure per-worker
 * auto-layout — equal parallel split, per-worker sequential layout, same-worker back-to-back
 * placement, the one-day disjoint-neighbor overlap, the uncovered virtual bar, and the 7300
 * half-day horizon reject (design §"Coverage and auto-layout → WorkerScheduleLayout.autoLayout").
 *
 * <p>The layout is a pure function — no persistence, no Spring context, no clock — so these
 * properties are cheap to run over hundreds of iterations against independent specification oracles.
 *
 * <ul>
 *   <li><b>Equal parallel split (R4.1):</b> every covering worker of a category gets a bar of
 *       {@code max(1, ceil(categoryHalfDays / n))} half-days, all starting on the same half-day
 *       (parallel).</li>
 *   <li><b>Uncovered virtual bar (R4.2):</b> an uncovered category produces exactly one null-member
 *       bar of the full category length, and the uncovered id list / crew-empty flag match the
 *       coverage.</li>
 *   <li><b>Per-worker sequential + back-to-back (R4.3):</b> each worker's bars, in layout order, are
 *       non-overlapping and each starts no earlier than the previous one's finish + 1; when they
 *       abut they are exactly back-to-back.</li>
 *   <li><b>Disjoint-neighbor overlap (R4.4):</b> a successor category whose worker set is disjoint
 *       from its predecessor may start up to 2 half-days before the predecessor's last finish, never
 *       below 1 and never before a same-worker cursor; a non-disjoint neighbor never overlaps.</li>
 *   <li><b>Horizon reject (R4.4):</b> a layout whose bars would finish beyond 7300 half-days is
 *       rejected with {@code error.schedule.too.long}.</li>
 * </ul>
 *
 * <p>Feature: FOR-05-10b-gantt-granularity, layout
 *
 * <p><b>Validates: Requirements 4.1, 4.2, 4.3, 4.4</b>
 */
@Tag("Feature: FOR-05-10b-gantt-granularity, layout")
class WorkerScheduleLayoutPropertyTest {

    private static final int MAX_HALF_DAY = ScheduleCalculator.MAX_HALF_DAY; // 7300

    // ------------------------------------------------------------------------------------------
    // Equal parallel split (R4.1): per-worker length = max(1, ceil(categoryHalfDays / n)); all of a
    // category's covering workers' bars share one start (parallel).
    // ------------------------------------------------------------------------------------------

    @Property(tries = 500)
    @Tag("Feature: FOR-05-10b-gantt-granularity, layout")
    void coveredCategoryIsSplitEquallyAndStartsInParallel(@ForAll("scenario") Scenario scenario) {
        LayoutResult result = WorkerScheduleLayout.autoLayout(scenario.categories, scenario.coverage);

        for (CategoryWork category : scenario.categories) {
            Long categoryId = category.workCategoryId();
            List<Long> covering = scenario.coverage.coveringWorkers(categoryId);
            if (covering.isEmpty()) {
                continue; // uncovered: separate property
            }
            List<WorkerBarLayout> catBars = barsOfCategory(result, categoryId);

            // One bar per covering worker, for exactly those workers (R4.1).
            assertThat(catBars.stream().map(WorkerBarLayout::projectMemberId).toList())
                    .as("covering workers of category %s", categoryId)
                    .containsExactlyInAnyOrderElementsOf(covering);

            int expectedPerWorker = WorkerScheduleLayout.perWorkerHalfDays(
                    category.categoryHalfDays(), covering.size());
            // All bars have the equal-split length.
            assertThat(catBars).allSatisfy(bar ->
                    assertThat(bar.durationHalfDays()).isEqualTo(expectedPerWorker));
            // The category's bars are placed at the shared provisional start unless a worker's own
            // sequential cursor (a bar they got from an earlier category) pushes them later: each
            // bar starts at max(workerCursor, categoryProvisionalStart) (D-layout). So all starts
            // are >= the provisional start, and the worker with the lowest cursor (at least one, the
            // ones with no earlier bar) sits exactly on the provisional start — i.e. the minimum
            // start equals the shared provisional start, and from there they run in parallel.
            int provisionalStart =
                    catBars.stream().mapToInt(WorkerBarLayout::startHalfDays).min().orElseThrow();
            assertThat(catBars)
                    .as("all covering workers of %s start at or after the provisional start",
                            categoryId)
                    .allSatisfy(bar ->
                            assertThat(bar.startHalfDays()).isGreaterThanOrEqualTo(provisionalStart));
        }
    }

    @Property(tries = 500)
    @Tag("Feature: FOR-05-10b-gantt-granularity, layout")
    void perWorkerSplitMatchesCeilingDivisionOracle(
            @ForAll @IntRange(min = 1, max = MAX_HALF_DAY) int categoryHalfDays,
            @ForAll @IntRange(min = 1, max = 20) int n) {

        int actual = WorkerScheduleLayout.perWorkerHalfDays(categoryHalfDays, n);

        // Independent oracle: ceiling division via BigInteger-free math, floored at 1.
        int expected = Math.max(1, (int) Math.ceil((double) categoryHalfDays / n));
        assertThat(actual).isEqualTo(expected);
        assertThat(actual).isGreaterThanOrEqualTo(1);
        // n parallel workers each doing `actual` cover at least the whole workload.
        assertThat((long) actual * n).isGreaterThanOrEqualTo(categoryHalfDays);
    }

    // ------------------------------------------------------------------------------------------
    // Uncovered virtual bar (R4.2): one null-member bar of the full category length; the uncovered
    // id list and crewEmpty flag reflect the coverage.
    // ------------------------------------------------------------------------------------------

    @Property(tries = 500)
    @Tag("Feature: FOR-05-10b-gantt-granularity, layout")
    void uncoveredCategoryYieldsExactlyOneFullLengthVirtualBar(
            @ForAll("scenario") Scenario scenario) {
        LayoutResult result = WorkerScheduleLayout.autoLayout(scenario.categories, scenario.coverage);

        for (CategoryWork category : scenario.categories) {
            Long categoryId = category.workCategoryId();
            if (!scenario.coverage.coveringWorkers(categoryId).isEmpty()) {
                continue; // covered: separate property
            }
            List<WorkerBarLayout> catBars = barsOfCategory(result, categoryId);
            assertThat(catBars).hasSize(1);
            WorkerBarLayout virtual = catBars.get(0);
            assertThat(virtual.projectMemberId()).isNull();
            assertThat(virtual.durationHalfDays()).isEqualTo(category.categoryHalfDays());
        }

        // The uncovered id list is exactly the categories with an empty covering list, in order.
        List<Long> expectedUncovered = scenario.categories.stream()
                .map(CategoryWork::workCategoryId)
                .filter(id -> scenario.coverage.coveringWorkers(id).isEmpty())
                .toList();
        assertThat(result.uncoveredCategoryIds()).containsExactlyElementsOf(expectedUncovered);

        // crewEmpty iff no category had any covering worker.
        boolean anyCovered = scenario.categories.stream()
                .anyMatch(c -> !scenario.coverage.coveringWorkers(c.workCategoryId()).isEmpty());
        assertThat(result.crewEmpty()).isEqualTo(!anyCovered);
    }

    @Property(tries = 300)
    @Tag("Feature: FOR-05-10b-gantt-granularity, layout")
    void everyBarMapsToExactlyOneInputCategory(@ForAll("scenario") Scenario scenario) {
        LayoutResult result = WorkerScheduleLayout.autoLayout(scenario.categories, scenario.coverage);

        Set<Long> inputCategoryIds = new HashSet<>();
        scenario.categories.forEach(c -> inputCategoryIds.add(c.workCategoryId()));

        assertThat(result.bars()).allSatisfy(bar ->
                assertThat(inputCategoryIds).contains(bar.workCategoryId()));
        // At most one virtual (null-member) bar per category.
        Map<Long, Long> virtualPerCategory = new HashMap<>();
        for (WorkerBarLayout bar : result.bars()) {
            if (bar.projectMemberId() == null) {
                virtualPerCategory.merge(bar.workCategoryId(), 1L, Long::sum);
            }
        }
        assertThat(virtualPerCategory.values()).allSatisfy(count -> assertThat(count).isEqualTo(1L));
    }

    // ------------------------------------------------------------------------------------------
    // Per-worker sequential layout + same-worker back-to-back (R4.3): a worker's bars, in layout
    // order, never overlap and each starts at >= previous finish + 1.
    // ------------------------------------------------------------------------------------------

    @Property(tries = 500)
    @Tag("Feature: FOR-05-10b-gantt-granularity, layout")
    void eachWorkerTimelineIsSequentialAndNonOverlapping(@ForAll("scenario") Scenario scenario) {
        LayoutResult result = WorkerScheduleLayout.autoLayout(scenario.categories, scenario.coverage);

        // Group real-worker bars by member in layout order.
        Map<Long, List<WorkerBarLayout>> byWorker = new HashMap<>();
        for (WorkerBarLayout bar : result.bars()) {
            if (bar.projectMemberId() != null) {
                byWorker.computeIfAbsent(bar.projectMemberId(), k -> new ArrayList<>()).add(bar);
            }
        }

        for (List<WorkerBarLayout> timeline : byWorker.values()) {
            for (int i = 1; i < timeline.size(); i++) {
                WorkerBarLayout prev = timeline.get(i - 1);
                WorkerBarLayout curr = timeline.get(i);
                // Sequential: next starts strictly after the previous bar's last half-day.
                assertThat(curr.startHalfDays())
                        .as("worker %s bar %d starts after previous finish",
                                curr.projectMemberId(), i)
                        .isGreaterThanOrEqualTo(prev.finishHalfDay() + 1);
            }
        }
    }

    @Property(tries = 500)
    @Tag("Feature: FOR-05-10b-gantt-granularity, layout")
    void allStartsAndFinishesAreOnTheGridAndWithinHorizon(@ForAll("scenario") Scenario scenario) {
        LayoutResult result = WorkerScheduleLayout.autoLayout(scenario.categories, scenario.coverage);

        assertThat(result.bars()).allSatisfy(bar -> {
            assertThat(bar.startHalfDays()).isGreaterThanOrEqualTo(1);
            assertThat(bar.durationHalfDays()).isGreaterThanOrEqualTo(1);
            assertThat(bar.finishHalfDay()).isLessThanOrEqualTo(MAX_HALF_DAY);
        });
    }

    // ------------------------------------------------------------------------------------------
    // Disjoint-neighbor overlap (R4.4): a successor category disjoint from its predecessor may be
    // pulled back up to 2 half-days; a non-disjoint neighbor never overlaps the predecessor.
    // ------------------------------------------------------------------------------------------

    @Property(tries = 500)
    @Tag("Feature: FOR-05-10b-gantt-granularity, layout")
    void neighborOverlapIsBoundedByOneDayAndOnlyBetweenDisjointWorkers(
            @ForAll("scenario") Scenario scenario) {
        LayoutResult result = WorkerScheduleLayout.autoLayout(scenario.categories, scenario.coverage);

        // Each category's provisional start (shared by its bars) and worker set, in input order.
        List<Long> order = scenario.categories.stream().map(CategoryWork::workCategoryId).toList();
        Map<Long, Integer> provisionalStart = new HashMap<>();
        Map<Long, Integer> lastFinish = new HashMap<>();
        Map<Long, Set<Long>> workerSet = new HashMap<>();
        for (Long categoryId : order) {
            List<WorkerBarLayout> catBars = barsOfCategory(result, categoryId);
            if (catBars.isEmpty()) {
                continue;
            }
            provisionalStart.put(categoryId,
                    catBars.stream().mapToInt(WorkerBarLayout::startHalfDays).min().orElseThrow());
            lastFinish.put(categoryId,
                    catBars.stream().mapToInt(WorkerBarLayout::finishHalfDay).max().orElseThrow());
            Set<Long> workers = new LinkedHashSet<>();
            catBars.forEach(b -> workers.add(b.projectMemberId())); // null = virtual worker
            workerSet.put(categoryId, workers);
        }

        for (int i = 1; i < order.size(); i++) {
            Long prevId = order.get(i - 1);
            Long currId = order.get(i);
            int predFinish = lastFinish.get(prevId);
            int currStart = provisionalStart.get(currId);

            boolean disjoint = isDisjoint(prevId, currId, workerSet);
            if (!disjoint) {
                // Non-disjoint (shares a real worker): the successor never starts before the
                // predecessor's last finish + 1 (no overlap).
                assertThat(currStart)
                        .as("non-disjoint neighbor %s after %s does not overlap", currId, prevId)
                        .isGreaterThanOrEqualTo(predFinish + 1);
            } else {
                // Disjoint: may be pulled back, but never more than 2 half-days before predFinish+1,
                // and never below 1.
                assertThat(currStart).isGreaterThanOrEqualTo(1);
                assertThat(currStart)
                        .as("disjoint neighbor %s overlap bounded by one day", currId)
                        .isGreaterThanOrEqualTo(
                                Math.max(1, predFinish + 1 - WorkerScheduleLayout.NEIGHBOR_OVERLAP_HALF_DAYS));
            }
        }
    }

    /**
     * Two categories are disjoint when they share no real worker. A virtual (null-member) worker of
     * one category is never the same person as any worker of another category, so two uncovered
     * neighbors are disjoint too — matching the layout's per-category virtual-worker sentinels.
     */
    private static boolean isDisjoint(Long a, Long b, Map<Long, Set<Long>> workerSet) {
        Set<Long> wa = workerSet.getOrDefault(a, Set.of());
        Set<Long> wb = workerSet.getOrDefault(b, Set.of());
        for (Long w : wa) {
            if (w != null && wb.contains(w)) {
                return false; // shares a real worker
            }
        }
        return true;
    }

    @Property(tries = 300)
    @Tag("Feature: FOR-05-10b-gantt-granularity, layout")
    void layoutIsDeterministic(@ForAll("scenario") Scenario scenario) {
        LayoutResult first = WorkerScheduleLayout.autoLayout(scenario.categories, scenario.coverage);
        LayoutResult second = WorkerScheduleLayout.autoLayout(scenario.categories, scenario.coverage);
        assertThat(second.bars()).isEqualTo(first.bars());
        assertThat(second.uncoveredCategoryIds()).isEqualTo(first.uncoveredCategoryIds());
        assertThat(second.crewEmpty()).isEqualTo(first.crewEmpty());
    }

    // ------------------------------------------------------------------------------------------
    // Horizon reject (R4.4): bars finishing beyond 7300 half-days reject the whole layout.
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-10b-gantt-granularity, layout")
    void aCategoryWorkloadBeyondHorizonIsRejected(
            @ForAll @IntRange(min = MAX_HALF_DAY + 1, max = MAX_HALF_DAY + 500) int tooBig) {
        // A single uncovered category whose full workload exceeds the horizon.
        List<CategoryWork> categories = List.of(new CategoryWork(1L, tooBig));
        Coverage coverage = CoverageResolver.resolve(List.of(1L), List.of());

        assertThatThrownBy(() -> WorkerScheduleLayout.autoLayout(categories, coverage))
                .isInstanceOf(ForemenApiException.class)
                .hasMessage("error.schedule.too.long");
    }

    @Property(tries = 200)
    @Tag("Feature: FOR-05-10b-gantt-granularity, layout")
    void manyFullHorizonCategoriesForOneWorkerEventuallyReject(
            @ForAll @IntRange(min = 2, max = 6) int categoryCount) {
        // One worker covering several categories each ~2/3 of the horizon: sequential layout for
        // that single worker must overrun the horizon and reject.
        int per = (MAX_HALF_DAY * 2) / 3;
        List<CategoryWork> categories = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= categoryCount; i++) {
            categories.add(new CategoryWork(i, per));
            ids.add(i);
        }
        // Single worker (id 1) covers all categories, so bars are strictly sequential (no overlap).
        List<WorkerMember> members = List.of(new WorkerMember(1L, new LinkedHashSet<>(ids)));
        Coverage coverage = CoverageResolver.resolve(ids, members);

        assertThatThrownBy(() -> WorkerScheduleLayout.autoLayout(categories, coverage))
                .isInstanceOf(ForemenApiException.class)
                .hasMessage("error.schedule.too.long");
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private static List<WorkerBarLayout> barsOfCategory(LayoutResult result, Long categoryId) {
        return result.bars().stream()
                .filter(b -> b.workCategoryId().equals(categoryId))
                .toList();
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /**
     * A layout scenario: an ordered list of categories (each with a small half-day workload, so
     * sequential chains stay well within the horizon) and a coverage resolved from a set of workers
     * drawn from a slightly wider category pool (so some categories are uncovered and some share
     * workers, exercising both the parallel split and the overlap rules).
     */
    record Scenario(List<CategoryWork> categories, Coverage coverage) {}

    @Provide
    Arbitrary<Scenario> scenario() {
        // Distinct category ids in a small pool; workloads small to keep chains in-horizon.
        Arbitrary<List<Long>> categoryIds =
                Arbitraries.longs().between(1L, 8L).set().ofMinSize(0).ofMaxSize(6)
                        .map(ArrayList::new);

        return categoryIds.flatMap(ids -> {
            Arbitrary<List<CategoryWork>> categories = buildCategories(ids);
            Arbitrary<List<WorkerMember>> members = buildMembers();
            return Combinators.combine(categories, members).as((cats, mems) -> {
                List<Long> catIds = cats.stream().map(CategoryWork::workCategoryId).toList();
                Coverage coverage = CoverageResolver.resolve(catIds, mems);
                return new Scenario(cats, coverage);
            });
        });
    }

    private Arbitrary<List<CategoryWork>> buildCategories(List<Long> ids) {
        if (ids.isEmpty()) {
            return Arbitraries.just(List.of());
        }
        // One workload per category id; small enough that even a single worker's whole chain stays
        // under 7300 half-days (6 categories * 60 half-days max = 360).
        return Arbitraries.integers().between(1, 60).list().ofSize(ids.size()).map(workloads -> {
            List<CategoryWork> cats = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                cats.add(new CategoryWork(ids.get(i), workloads.get(i)));
            }
            return cats;
        });
    }

    private Arbitrary<List<WorkerMember>> buildMembers() {
        Arbitrary<Set<Long>> memberCategories =
                Arbitraries.longs().between(1L, 10L).set().ofMinSize(0).ofMaxSize(5);
        Arbitrary<List<Long>> memberIds =
                Arbitraries.longs().between(1L, 40L).set().ofMinSize(0).ofMaxSize(5)
                        .map(ArrayList::new);
        return Combinators.combine(memberIds, memberCategories.list().ofMinSize(0).ofMaxSize(5))
                .as((idList, catSets) -> {
                    List<WorkerMember> list = new ArrayList<>();
                    int n = Math.min(idList.size(), catSets.size());
                    for (int i = 0; i < n; i++) {
                        list.add(new WorkerMember(idList.get(i), catSets.get(i)));
                    }
                    return list;
                });
    }
}
