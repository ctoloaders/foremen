package com.foremen.service.schedule;

import com.foremen.service.schedule.CoverageResolver.Coverage;
import com.foremen.service.schedule.CoverageResolver.WorkerMember;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for the FOR-05-10b {@link CoverageResolver}: the per-category covering-worker
 * resolution and the {@code Uncovered_Category} set (design §"Coverage and auto-layout →
 * CoverageResolver").
 *
 * <p>The resolver is a pure function — no persistence, no Spring context, no clock — so these
 * properties are cheap to run over hundreds of iterations against an independent specification
 * oracle.
 *
 * <ul>
 *   <li><b>Coverage oracle (R4.1):</b> a member covers a category iff its category set contains that
 *       category, and the covering list is exactly those members in ascending id order.</li>
 *   <li><b>Uncovered partition (R4.2, R8.2):</b> the uncovered set is exactly the categories with an
 *       empty covering list; {@code covered} and {@code uncovered} partition the input categories.</li>
 *   <li><b>Order & shape invariants:</b> covering lists are sorted and distinct, the key set equals
 *       the de-duplicated input categories in order, and the result is independent of member input
 *       order.</li>
 * </ul>
 *
 * <p>Feature: FOR-05-10b-gantt-granularity, coverage
 *
 * <p><b>Validates: Requirements 4.1, 4.2, 8.2</b>
 */
@Tag("Feature: FOR-05-10b-gantt-granularity, coverage")
class CoverageResolverPropertyTest {

    // ------------------------------------------------------------------------------------------
    // Coverage oracle (R4.1): a member covers a category iff its set contains it; the covering list
    // is exactly those members, in ascending id order, deduplicated.
    // ------------------------------------------------------------------------------------------

    @Property(tries = 500)
    @Tag("Feature: FOR-05-10b-gantt-granularity, coverage")
    void coveringListMatchesTheMembershipOracle(@ForAll("scenario") Scenario scenario) {
        Coverage coverage = CoverageResolver.resolve(scenario.categoryIds, scenario.members);

        Set<Long> distinctCategories = new HashSet<>(scenario.categoryIds);
        for (Long category : distinctCategories) {
            // Independent oracle: every member whose set contains the category, by ascending id.
            List<Long> expected = scenario.members.stream()
                    .filter(m -> m.workCategoryIds().contains(category))
                    .map(WorkerMember::memberId)
                    .distinct()
                    .sorted()
                    .toList();

            assertThat(coverage.coveringWorkers(category))
                    .as("covering workers of category %s", category)
                    .isEqualTo(expected);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Uncovered partition (R4.2, R8.2): uncovered = { c : coveringWorkers(c).isEmpty() }, and
    // covered/uncovered partition the de-duplicated input categories.
    // ------------------------------------------------------------------------------------------

    @Property(tries = 500)
    @Tag("Feature: FOR-05-10b-gantt-granularity, coverage")
    void uncoveredIsExactlyTheCategoriesWithAnEmptyCoveringList(
            @ForAll("scenario") Scenario scenario) {
        Coverage coverage = CoverageResolver.resolve(scenario.categoryIds, scenario.members);

        for (Long category : new HashSet<>(scenario.categoryIds)) {
            boolean empty = coverage.coveringWorkers(category).isEmpty();
            assertThat(coverage.isUncovered(category))
                    .as("isUncovered(%s) vs empty covering list", category)
                    .isEqualTo(empty);
            assertThat(coverage.covered(category)).isEqualTo(!empty);
        }
    }

    @Property(tries = 500)
    @Tag("Feature: FOR-05-10b-gantt-granularity, coverage")
    void coveredAndUncoveredPartitionTheInputCategories(@ForAll("scenario") Scenario scenario) {
        Coverage coverage = CoverageResolver.resolve(scenario.categoryIds, scenario.members);

        Set<Long> distinctCategories = new HashSet<>(scenario.categoryIds);

        // Every resolved key is either covered or uncovered, never both, never neither.
        assertThat(coverage.coveringMembers().keySet()).isEqualTo(distinctCategories);
        for (Long category : distinctCategories) {
            assertThat(coverage.covered(category) ^ coverage.isUncovered(category))
                    .as("exactly one of covered/uncovered for %s", category)
                    .isTrue();
        }
        // The uncovered set is a subset of the input categories.
        assertThat(distinctCategories).containsAll(coverage.uncovered());
    }

    // ------------------------------------------------------------------------------------------
    // Shape & order invariants.
    // ------------------------------------------------------------------------------------------

    @Property(tries = 500)
    @Tag("Feature: FOR-05-10b-gantt-granularity, coverage")
    void coveringListsAreSortedAndDistinct(@ForAll("scenario") Scenario scenario) {
        Coverage coverage = CoverageResolver.resolve(scenario.categoryIds, scenario.members);

        for (List<Long> covering : coverage.coveringMembers().values()) {
            assertThat(covering).isSorted();
            assertThat(covering).doesNotHaveDuplicates();
        }
    }

    @Property(tries = 500)
    @Tag("Feature: FOR-05-10b-gantt-granularity, coverage")
    void keySetEqualsDeduplicatedInputCategoriesInOrder(@ForAll("scenario") Scenario scenario) {
        Coverage coverage = CoverageResolver.resolve(scenario.categoryIds, scenario.members);

        List<Long> expectedOrder = scenario.categoryIds.stream().distinct().toList();
        assertThat(coverage.coveringMembers().keySet()).containsExactlyElementsOf(expectedOrder);
    }

    @Property(tries = 300)
    @Tag("Feature: FOR-05-10b-gantt-granularity, coverage")
    void resultIsIndependentOfMemberInputOrder(@ForAll("scenario") Scenario scenario) {
        Coverage first = CoverageResolver.resolve(scenario.categoryIds, scenario.members);

        List<WorkerMember> reversed = new ArrayList<>(scenario.members);
        java.util.Collections.reverse(reversed);
        Coverage second = CoverageResolver.resolve(scenario.categoryIds, reversed);

        assertThat(second.coveringMembers()).isEqualTo(first.coveringMembers());
        assertThat(second.uncovered()).isEqualTo(first.uncovered());
    }

    @Property(tries = 300)
    @Tag("Feature: FOR-05-10b-gantt-granularity, coverage")
    void emptyMemberSetMakesEveryCategoryUncovered(@ForAll("categoryIds") List<Long> categoryIds) {
        Coverage coverage = CoverageResolver.resolve(categoryIds, List.of());

        assertThat(coverage.uncovered()).containsExactlyElementsOf(categoryIds.stream().distinct().toList());
        for (Long category : categoryIds) {
            assertThat(coverage.coveringWorkers(category)).isEmpty();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /** A coverage scenario: a list of category ids (possibly duplicated) and a set of members. */
    record Scenario(List<Long> categoryIds, List<WorkerMember> members) {}

    /** A small pool of category ids keeps overlap between members and categories likely. */
    @Provide
    Arbitrary<List<Long>> categoryIds() {
        return Arbitraries.longs().between(1L, 12L).list().ofMinSize(0).ofMaxSize(8);
    }

    @Provide
    Arbitrary<Scenario> scenario() {
        Arbitrary<List<Long>> categories = categoryIds();

        // Members draw category ids from a slightly wider pool so some lists cover nothing in the
        // estimate (producing uncovered categories) and some overlap (producing multi-worker rows).
        Arbitrary<Set<Long>> memberCategories =
                Arbitraries.longs().between(1L, 14L).set().ofMinSize(0).ofMaxSize(6);

        Arbitrary<List<WorkerMember>> members = Combinators
                .combine(uniqueMemberIds(), memberCategories.list().ofMinSize(0).ofMaxSize(6))
                .as((ids, catSets) -> {
                    List<WorkerMember> list = new ArrayList<>();
                    int n = Math.min(ids.size(), catSets.size());
                    for (int i = 0; i < n; i++) {
                        list.add(new WorkerMember(ids.get(i), catSets.get(i)));
                    }
                    return list;
                });

        return Combinators.combine(categories, members).as(Scenario::new);
    }

    /** Distinct member ids so each member is a distinct worker. */
    private Arbitrary<List<Long>> uniqueMemberIds() {
        return Arbitraries.longs().between(1L, 50L).set().ofMinSize(0).ofMaxSize(6)
                .map(ArrayList::new);
    }
}
