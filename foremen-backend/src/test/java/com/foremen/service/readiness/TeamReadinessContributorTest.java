package com.foremen.service.readiness;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TeamReadinessContributor}'s team-state → aggregate state/percentage mapping
 * (FOR-05-11 Requirement 5.5, 5.7).
 *
 * <p>The derivation is exercised directly through its pure, Spring-free entry point
 * ({@link TeamReadinessContributor#deriveContribution}), so no persistence or Spring context is
 * needed.
 *
 * <p>Feature: FOR-05-11-project-readiness
 *
 * <p><b>Validates: Requirements 5.5, 5.7</b>
 */
class TeamReadinessContributorTest {

    @Test
    void doneTeamIsDoneAtHundredWithNoItems() {
        AreaContribution contribution =
                TeamReadinessContributor.deriveContribution(com.foremen.service.team.ReadinessState.DONE);

        assertCommonShape(contribution);
        assertThat(contribution.state()).isEqualTo(ReadinessState.DONE);
        assertThat(contribution.percentage()).isEqualTo(100);
        assertThat(contribution.items()).isEmpty();
    }

    @Test
    void blockedTeamIsBlockedAtZeroWithNoForemanItem() {
        AreaContribution contribution =
                TeamReadinessContributor.deriveContribution(com.foremen.service.team.ReadinessState.BLOCKED);

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
    void itemsAreEmptyIffDone() {
        for (com.foremen.service.team.ReadinessState teamState : com.foremen.service.team.ReadinessState.values()) {
            AreaContribution contribution = TeamReadinessContributor.deriveContribution(teamState);
            boolean fullyReady =
                    contribution.state() == ReadinessState.DONE && contribution.percentage() == 100;
            assertThat(contribution.items().isEmpty())
                    .as("items empty iff done for team state %s", teamState)
                    .isEqualTo(fullyReady);
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
