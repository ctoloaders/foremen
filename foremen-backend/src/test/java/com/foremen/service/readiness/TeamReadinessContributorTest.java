package com.foremen.service.readiness;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TeamReadinessContributor}'s member-count → aggregate state/percentage/items
 * mapping (FOR-05-11 Requirement 5.5, 5.7; FOR-05-10b Requirement 8.1, 8.3, 8.4).
 *
 * <p>The derivation is exercised directly through its pure, Spring-free entry point
 * ({@link TeamReadinessContributor#deriveContribution(boolean, int)}), so no persistence or Spring
 * context is needed.
 *
 * <p>Feature: FOR-05-10b-gantt-granularity
 *
 * <p><b>Validates: Requirements 8.1, 8.3, 8.4</b>
 */
class TeamReadinessContributorTest {

    @Test
    void foremanAndWorkerPresentIsDoneAtHundredWithNoItems() {
        AreaContribution contribution = TeamReadinessContributor.deriveContribution(true, 1);

        assertCommonShape(contribution);
        assertThat(contribution.state()).isEqualTo(ReadinessState.DONE);
        assertThat(contribution.percentage()).isEqualTo(100);
        assertThat(contribution.items()).isEmpty();
    }

    @Test
    void noForemanIsBlockedAtZeroWithNoForemanItem() {
        AreaContribution contribution = TeamReadinessContributor.deriveContribution(false, 1);

        assertCommonShape(contribution);
        assertThat(contribution.state()).isEqualTo(ReadinessState.BLOCKED);
        assertThat(contribution.percentage()).isZero();
        assertThat(contribution.items()).singleElement().satisfies(item -> {
            assertThat(item.descriptionKey()).isEqualTo("readiness.team.noForeman");
            assertThat(item.params()).isEmpty();
            assertThat(item.targetTab()).isEqualTo("team");
        });
    }

    @Test
    void noWorkerIsBlockedAtZeroWithNoWorkersItem() {
        AreaContribution contribution = TeamReadinessContributor.deriveContribution(true, 0);

        assertCommonShape(contribution);
        assertThat(contribution.state()).isEqualTo(ReadinessState.BLOCKED);
        assertThat(contribution.percentage()).isZero();
        assertThat(contribution.items()).singleElement().satisfies(item -> {
            assertThat(item.descriptionKey()).isEqualTo("readiness.team.noWorkers");
            assertThat(item.params()).isEmpty();
            assertThat(item.targetTab()).isEqualTo("team");
        });
    }

    @Test
    void noForemanAndNoWorkerShowsBothBlockersTogether() {
        AreaContribution contribution = TeamReadinessContributor.deriveContribution(false, 0);

        assertCommonShape(contribution);
        assertThat(contribution.state()).isEqualTo(ReadinessState.BLOCKED);
        assertThat(contribution.percentage()).isZero();
        assertThat(contribution.items())
                .extracting(OutstandingItem::descriptionKey)
                .containsExactly("readiness.team.noForeman", "readiness.team.noWorkers");
        assertThat(contribution.items())
                .allSatisfy(item -> {
                    assertThat(item.params()).isEmpty();
                    assertThat(item.targetTab()).isEqualTo("team");
                });
    }

    @Test
    void itemsAreEmptyIffForemanAndWorkerPresent() {
        boolean[] bools = {true, false};
        int[] workerCounts = {0, 1, 3};
        for (boolean hasForeman : bools) {
            for (int workers : workerCounts) {
                AreaContribution contribution =
                        TeamReadinessContributor.deriveContribution(hasForeman, workers);
                boolean fullyReady = hasForeman && workers >= 1;
                assertThat(contribution.items().isEmpty())
                        .as("items empty iff foreman=%s workers=%d", hasForeman, workers)
                        .isEqualTo(fullyReady);
                assertThat(contribution.state() == ReadinessState.DONE)
                        .as("DONE iff foreman=%s workers=%d", hasForeman, workers)
                        .isEqualTo(fullyReady);
                assertThat(contribution.percentage()).isEqualTo(fullyReady ? 100 : 0);
            }
        }
    }

    @Test
    void teamAreaKeyResourceAndOrderAreStable() {
        TeamReadinessContributor contributor = new TeamReadinessContributor(null);

        assertThat(contributor.areaKey()).isEqualTo("team");
        assertThat(contributor.areaResource()).isEqualTo("PROJECT_MEMBERS");
        assertThat(contributor.order()).isEqualTo(50);
    }

    private static void assertCommonShape(AreaContribution contribution) {
        assertThat(contribution.key()).isEqualTo("team");
        assertThat(contribution.nameKey()).isEqualTo("workspace.tab.team");
        assertThat(contribution.percentage()).isBetween(0, 100);
    }
}
