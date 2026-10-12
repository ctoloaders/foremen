package com.foremen.service.schedule;

import com.foremen.service.schedule.CoverageResolver.Coverage;
import com.foremen.service.schedule.CoverageResolver.WorkerMember;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Example-based unit tests for the FOR-05-10b {@link CoverageResolver}: the per-category covering
 * workers and the {@code Uncovered_Category} set derived from the ACTIVE WORKER members' categories
 * (design §"Coverage and auto-layout → CoverageResolver").
 *
 * <p>These pin down the concrete coverage decisions and ordering a regression would break (the
 * universal rules are covered by {@link CoverageResolverPropertyTest}).
 *
 * <p><b>Validates: Requirements 4.1, 4.2, 8.2</b>
 */
class CoverageResolverTest {

    private static WorkerMember member(long id, Long... categoryIds) {
        return new WorkerMember(id, Set.of(categoryIds));
    }

    @Nested
    @DisplayName("covering workers per category (R4.1)")
    class CoveringWorkers {

        @Test
        @DisplayName("a category is covered by every member whose list contains it")
        void coveredByEveryMemberWithTheCategory() {
            Coverage coverage = CoverageResolver.resolve(
                    List.of(10L, 20L),
                    List.of(member(1L, 10L), member(2L, 10L, 20L), member(3L, 20L)));

            assertThat(coverage.coveringWorkers(10L)).containsExactly(1L, 2L);
            assertThat(coverage.coveringWorkers(20L)).containsExactly(2L, 3L);
            assertThat(coverage.covered(10L)).isTrue();
            assertThat(coverage.covered(20L)).isTrue();
            assertThat(coverage.uncovered()).isEmpty();
        }

        @Test
        @DisplayName("covering members are ordered by member id ascending regardless of input order")
        void coveringMembersOrderedByIdAscending() {
            Coverage coverage = CoverageResolver.resolve(
                    List.of(10L),
                    List.of(member(5L, 10L), member(1L, 10L), member(3L, 10L)));

            assertThat(coverage.coveringWorkers(10L)).containsExactly(1L, 3L, 5L);
        }

        @Test
        @DisplayName("a member covering several categories appears in each category's list")
        void memberWithManyCategoriesAppearsInEach() {
            Coverage coverage = CoverageResolver.resolve(
                    List.of(10L, 20L, 30L),
                    List.of(member(1L, 10L, 20L, 30L)));

            assertThat(coverage.coveringWorkers(10L)).containsExactly(1L);
            assertThat(coverage.coveringWorkers(20L)).containsExactly(1L);
            assertThat(coverage.coveringWorkers(30L)).containsExactly(1L);
        }
    }

    @Nested
    @DisplayName("uncovered categories (R4.2, R8.2)")
    class Uncovered {

        @Test
        @DisplayName("a category no member covers is uncovered and maps to an empty list")
        void categoryWithNoCoveringMemberIsUncovered() {
            Coverage coverage = CoverageResolver.resolve(
                    List.of(10L, 99L),
                    List.of(member(1L, 10L)));

            assertThat(coverage.coveringWorkers(99L)).isEmpty();
            assertThat(coverage.covered(99L)).isFalse();
            assertThat(coverage.isUncovered(99L)).isTrue();
            assertThat(coverage.uncovered()).containsExactly(99L);
        }

        @Test
        @DisplayName("with no members at all every category is uncovered")
        void noMembersMakesEveryCategoryUncovered() {
            Coverage coverage = CoverageResolver.resolve(
                    List.of(10L, 20L, 30L), List.of());

            assertThat(coverage.uncovered()).containsExactly(10L, 20L, 30L);
            assertThat(coverage.coveringWorkers(10L)).isEmpty();
            assertThat(coverage.coveringWorkers(20L)).isEmpty();
            assertThat(coverage.coveringWorkers(30L)).isEmpty();
        }

        @Test
        @DisplayName("the uncovered set preserves the input category order")
        void uncoveredPreservesInputOrder() {
            Coverage coverage = CoverageResolver.resolve(
                    List.of(30L, 10L, 20L), List.of());

            assertThat(coverage.uncovered()).containsExactly(30L, 10L, 20L);
        }
    }

    @Nested
    @DisplayName("degenerate inputs")
    class Degenerate {

        @Test
        @DisplayName("no categories yields an empty coverage and empty uncovered set")
        void noCategories() {
            Coverage coverage = CoverageResolver.resolve(List.of(), List.of(member(1L, 10L)));

            assertThat(coverage.coveringMembers()).isEmpty();
            assertThat(coverage.uncovered()).isEmpty();
        }

        @Test
        @DisplayName("null categories and null members are treated as empty")
        void nullInputs() {
            Coverage coverage = CoverageResolver.resolve(null, null);

            assertThat(coverage.coveringMembers()).isEmpty();
            assertThat(coverage.uncovered()).isEmpty();
        }

        @Test
        @DisplayName("null entries inside the collections are ignored")
        void nullEntriesIgnored() {
            Coverage coverage = CoverageResolver.resolve(
                    java.util.Arrays.asList(10L, null, 20L),
                    java.util.Arrays.asList(member(1L, 10L), null));

            assertThat(coverage.coveringMembers().keySet()).containsExactly(10L, 20L);
            assertThat(coverage.coveringWorkers(10L)).containsExactly(1L);
            assertThat(coverage.uncovered()).containsExactly(20L);
        }

        @Test
        @DisplayName("a duplicated category id is resolved once, keeping the first occurrence")
        void duplicateCategoryResolvedOnce() {
            Coverage coverage = CoverageResolver.resolve(
                    List.of(10L, 10L, 20L),
                    List.of(member(1L, 10L)));

            assertThat(coverage.coveringMembers().keySet()).containsExactly(10L, 20L);
            assertThat(coverage.coveringWorkers(10L)).containsExactly(1L);
        }

        @Test
        @DisplayName("a member with no categories covers nothing")
        void memberWithNoCategoriesCoversNothing() {
            Coverage coverage = CoverageResolver.resolve(
                    List.of(10L), List.of(new WorkerMember(1L, Set.of())));

            assertThat(coverage.coveringWorkers(10L)).isEmpty();
            assertThat(coverage.uncovered()).containsExactly(10L);
        }
    }

    @Nested
    @DisplayName("WorkerMember construction")
    class WorkerMemberConstruction {

        @Test
        @DisplayName("a null member id is rejected")
        void nullMemberIdRejected() {
            assertThatThrownBy(() -> new WorkerMember(null, Set.of(10L)))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("a null category set is treated as empty")
        void nullCategorySetTreatedAsEmpty() {
            WorkerMember member = new WorkerMember(1L, null);
            assertThat(member.workCategoryIds()).isEmpty();
        }

        @Test
        @DisplayName("null category ids inside the set are dropped")
        void nullCategoryIdsDropped() {
            WorkerMember member = new WorkerMember(1L, java.util.stream.Stream.of(10L, null, 20L)
                    .collect(java.util.HashSet::new, java.util.HashSet::add, java.util.HashSet::addAll));
            assertThat(member.workCategoryIds()).containsExactlyInAnyOrder(10L, 20L);
        }
    }

    @Test
    @DisplayName("coverage results are immutable snapshots")
    void resultsAreImmutable() {
        Coverage coverage = CoverageResolver.resolve(List.of(10L), List.of(member(1L, 10L)));

        assertThatThrownBy(() -> coverage.uncovered().add(99L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> coverage.coveringMembers().put(99L, List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> coverage.coveringWorkers(10L).add(2L))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
