package com.foremen.service.readiness;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.foremen.controller.model.schedule.ScheduleReadiness;
import com.foremen.controller.model.schedule.UncoveredCategoryRef;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ScheduleReadinessContributor}'s schedule-gate → aggregate state/percentage
 * mapping (FOR-05-11 Requirement 5.6, 5.7) and the FOR-05-10b coverage dimension (R8.2, R8.3,
 * R8.4): the {@code readiness.schedule.noWorkerForCategory} items and the BLOCKED override.
 *
 * <p>The derivation is exercised directly through its pure, Spring-free entry point
 * ({@link ScheduleReadinessContributor#deriveContribution}), so no persistence or Spring context is
 * needed.
 *
 * <p>Feature: FOR-05-11-project-readiness / FOR-05-10b-gantt-granularity
 *
 * <p><b>Validates: Requirements 5.6, 5.7 (FOR-05-11); 8.2, 8.3, 8.4 (FOR-05-10b)</b>
 */
class ScheduleReadinessContributorTest {

    @Test
    void fullyScheduledIsDoneAtHundredWithNoItems() {
        AreaContribution contribution = ScheduleReadinessContributor.deriveContribution(
                readiness(ScheduleReadiness.State.DONE, 4, 4));

        assertCommonShape(contribution);
        assertThat(contribution.state()).isEqualTo(ReadinessState.DONE);
        assertThat(contribution.percentage()).isEqualTo(100);
        assertThat(contribution.items()).isEmpty();
    }

    @Test
    void partiallyScheduledIsPartialWithScheduledFractionAndUnscheduledItem() {
        AreaContribution contribution = ScheduleReadinessContributor.deriveContribution(
                readiness(ScheduleReadiness.State.PARTIAL, 4, 1));

        assertCommonShape(contribution);
        assertThat(contribution.state()).isEqualTo(ReadinessState.PARTIAL);
        assertThat(contribution.percentage()).isEqualTo(25); // round(100 * 1 / 4)
        assertThat(contribution.items()).singleElement().satisfies(item -> {
            assertThat(item.descriptionKey()).isEqualTo("readiness.schedule.unscheduledRows");
            assertThat(item.params()).containsExactly(Map.entry("count", 3));
            assertThat(item.targetTab()).isEqualTo("scheduleDesign");
        });
    }

    @Test
    void nothingScheduledWithRowsIsBlockedAtZeroWithAllRowsUnscheduled() {
        AreaContribution contribution = ScheduleReadinessContributor.deriveContribution(
                readiness(ScheduleReadiness.State.BLOCKED, 3, 0));

        assertCommonShape(contribution);
        assertThat(contribution.state()).isEqualTo(ReadinessState.BLOCKED);
        assertThat(contribution.percentage()).isZero();
        assertThat(contribution.items()).singleElement().satisfies(item ->
                assertThat(item.params()).containsExactly(Map.entry("count", 3)));
    }

    @Test
    void noRowsIsBlockedAtZeroWithZeroUnscheduledCount() {
        AreaContribution contribution = ScheduleReadinessContributor.deriveContribution(
                readiness(ScheduleReadiness.State.BLOCKED, 0, 0));

        assertCommonShape(contribution);
        assertThat(contribution.state()).isEqualTo(ReadinessState.BLOCKED);
        assertThat(contribution.percentage()).isZero();
        assertThat(contribution.items()).singleElement().satisfies(item -> {
            assertThat(item.descriptionKey()).isEqualTo("readiness.schedule.unscheduledRows");
            assertThat(item.params()).containsExactly(Map.entry("count", 0));
            assertThat(item.targetTab()).isEqualTo("scheduleDesign");
        });
    }

    @Test
    void percentageRoundsHalfUp() {
        // 1 of 3 scheduled → 100/3 = 33.33 → 33; 2 of 3 → 66.67 → 67.
        assertThat(ScheduleReadinessContributor
                .deriveContribution(readiness(ScheduleReadiness.State.PARTIAL, 3, 1)).percentage())
                .isEqualTo(33);
        assertThat(ScheduleReadinessContributor
                .deriveContribution(readiness(ScheduleReadiness.State.PARTIAL, 3, 2)).percentage())
                .isEqualTo(67);
    }

    @Test
    void itemsAreEmptyIffDone() {
        AreaContribution[] contributions = {
                ScheduleReadinessContributor.deriveContribution(readiness(ScheduleReadiness.State.DONE, 2, 2)),
                ScheduleReadinessContributor.deriveContribution(readiness(ScheduleReadiness.State.PARTIAL, 2, 1)),
                ScheduleReadinessContributor.deriveContribution(readiness(ScheduleReadiness.State.BLOCKED, 2, 0)),
                ScheduleReadinessContributor.deriveContribution(readiness(ScheduleReadiness.State.BLOCKED, 0, 0)),
        };
        for (AreaContribution contribution : contributions) {
            boolean fullyReady =
                    contribution.state() == ReadinessState.DONE && contribution.percentage() == 100;
            assertThat(contribution.items().isEmpty())
                    .as("items empty iff done for %s", contribution.state())
                    .isEqualTo(fullyReady);
        }
    }

    @Test
    void scheduleAreaKeyResourceAndOrderAreStable() {
        ScheduleReadinessContributor contributor = new ScheduleReadinessContributor(null);

        assertThat(contributor.areaKey()).isEqualTo("schedule");
        assertThat(contributor.areaResource()).isEqualTo("WORK_SCHEDULE");
        assertThat(contributor.order()).isEqualTo(60);
    }

    // --- FOR-05-10b R8.2 / R8.3 / R8.4: uncovered-category coverage dimension ---

    @Test
    void oneUncoveredCategoryEmitsOneNoWorkerItemTargetingTeam() {
        AreaContribution contribution = ScheduleReadinessContributor.deriveContribution(
                readiness(ScheduleReadiness.State.DONE, 2, 2,
                        new UncoveredCategoryRef(5L, "Tynkowanie")));

        assertCommonShape(contribution);
        // Any uncovered category forces BLOCKED even though every row carries a bar (R8.2).
        assertThat(contribution.state()).isEqualTo(ReadinessState.BLOCKED);
        assertThat(contribution.items()).singleElement().satisfies(item -> {
            assertThat(item.descriptionKey()).isEqualTo("readiness.schedule.noWorkerForCategory");
            assertThat(item.params()).containsExactly(Map.entry("category", "Tynkowanie"));
            assertThat(item.targetTab()).isEqualTo("team");
        });
    }

    @Test
    void uncoveredAndUnscheduledBlockersAreShownTogether() {
        // Rows partially scheduled AND a category uncovered: both blockers must be visible (R8.3/8.4).
        AreaContribution contribution = ScheduleReadinessContributor.deriveContribution(
                readiness(ScheduleReadiness.State.PARTIAL, 3, 1,
                        new UncoveredCategoryRef(7L, "Malowanie"),
                        new UncoveredCategoryRef(8L, "Hydraulika")));

        assertCommonShape(contribution);
        assertThat(contribution.state()).isEqualTo(ReadinessState.BLOCKED);
        assertThat(contribution.items()).hasSize(3);
        // The unscheduled-rows item is first (count = 3 - 1 = 2), targeting the schedule tab.
        assertThat(contribution.items().get(0).descriptionKey())
                .isEqualTo("readiness.schedule.unscheduledRows");
        assertThat(contribution.items().get(0).params()).containsExactly(Map.entry("count", 2));
        assertThat(contribution.items().get(0).targetTab()).isEqualTo("scheduleDesign");
        // Then one noWorkerForCategory item per uncovered category, in order, targeting the team tab.
        assertThat(contribution.items().get(1).descriptionKey())
                .isEqualTo("readiness.schedule.noWorkerForCategory");
        assertThat(contribution.items().get(1).params()).containsExactly(Map.entry("category", "Malowanie"));
        assertThat(contribution.items().get(1).targetTab()).isEqualTo("team");
        assertThat(contribution.items().get(2).params()).containsExactly(Map.entry("category", "Hydraulika"));
        assertThat(contribution.items().get(2).targetTab()).isEqualTo("team");
    }

    @Test
    void noUncoveredCategoriesKeepsFor0510Behavior() {
        // With no uncovered category the FOR-05-10 state/items mapping is unchanged (R8.3).
        AreaContribution done = ScheduleReadinessContributor.deriveContribution(
                readiness(ScheduleReadiness.State.DONE, 2, 2));
        assertThat(done.state()).isEqualTo(ReadinessState.DONE);
        assertThat(done.items()).isEmpty();

        AreaContribution partial = ScheduleReadinessContributor.deriveContribution(
                readiness(ScheduleReadiness.State.PARTIAL, 2, 1));
        assertThat(partial.state()).isEqualTo(ReadinessState.PARTIAL);
        assertThat(partial.items()).singleElement().satisfies(item ->
                assertThat(item.descriptionKey()).isEqualTo("readiness.schedule.unscheduledRows"));
    }

    @Test
    void nullUncoveredNameFallsBackToEmptyParam() {
        // A defensive guard: a null localized name must not NPE Map.of(..) — it becomes "".
        AreaContribution contribution = ScheduleReadinessContributor.deriveContribution(
                readiness(ScheduleReadiness.State.DONE, 1, 1,
                        new UncoveredCategoryRef(9L, null)));

        assertThat(contribution.state()).isEqualTo(ReadinessState.BLOCKED);
        assertThat(contribution.items()).singleElement().satisfies(item -> {
            assertThat(item.descriptionKey()).isEqualTo("readiness.schedule.noWorkerForCategory");
            assertThat(item.params()).containsExactly(Map.entry("category", ""));
        });
    }

    private static ScheduleReadiness readiness(ScheduleReadiness.State state, int rowCount, int scheduledCount) {
        return new ScheduleReadiness(
                ScheduleReadiness.KEY, state, rowCount, scheduledCount, List.of(), List.of());
    }

    private static ScheduleReadiness readiness(
            ScheduleReadiness.State state, int rowCount, int scheduledCount, UncoveredCategoryRef... uncovered) {
        List<UncoveredCategoryRef> refs = List.of(uncovered);
        List<Long> ids = refs.stream().map(UncoveredCategoryRef::workCategoryId).toList();
        return new ScheduleReadiness(
                ScheduleReadiness.KEY, state, rowCount, scheduledCount, ids, refs);
    }

    private static void assertCommonShape(AreaContribution contribution) {
        assertThat(contribution.key()).isEqualTo("schedule");
        assertThat(contribution.nameKey()).isEqualTo("workspace.tab.scheduleDesign");
        assertThat(contribution.percentage()).isBetween(0, 100);
    }
}
