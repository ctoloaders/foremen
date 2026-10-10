package com.foremen.service.readiness;

import org.junit.jupiter.api.Test;

import com.foremen.controller.model.schedule.ScheduleReadiness;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ScheduleReadinessContributor}'s schedule-gate → aggregate state/percentage
 * mapping (FOR-05-11 Requirement 5.6, 5.7).
 *
 * <p>The derivation is exercised directly through its pure, Spring-free entry point
 * ({@link ScheduleReadinessContributor#deriveContribution}), so no persistence or Spring context is
 * needed.
 *
 * <p>Feature: FOR-05-11-project-readiness
 *
 * <p><b>Validates: Requirements 5.6, 5.7</b>
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
            assertThat(item.params()).containsExactly(java.util.Map.entry("count", 3));
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
                assertThat(item.params()).containsExactly(java.util.Map.entry("count", 3)));
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
            assertThat(item.params()).containsExactly(java.util.Map.entry("count", 0));
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

    private static ScheduleReadiness readiness(ScheduleReadiness.State state, int rowCount, int scheduledCount) {
        return new ScheduleReadiness(ScheduleReadiness.KEY, state, rowCount, scheduledCount);
    }

    private static void assertCommonShape(AreaContribution contribution) {
        assertThat(contribution.key()).isEqualTo("schedule");
        assertThat(contribution.nameKey()).isEqualTo("workspace.tab.scheduleDesign");
        assertThat(contribution.percentage()).isBetween(0, 100);
    }
}
