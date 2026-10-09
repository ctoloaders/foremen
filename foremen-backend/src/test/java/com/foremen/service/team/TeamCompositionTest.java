package com.foremen.service.team;

import com.foremen.service.team.TeamComposition.Violation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure {@link TeamComposition} helper (FOR-05-09 task 6.3, Requirement 6 criteria
 * 1, 3, 6): the Assignable_Project_Role membership test and the {@code role.not.assignable} /
 * {@code user.inactive} sub-order precedence.
 */
class TeamCompositionTest {

    // --- isAssignableProjectRole (Req 6.1) ---

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "FOREMAN", "ESTIMATOR", "WORKER", "FINANCIER", "CLIENT"})
    @DisplayName("the six system role codes are Assignable_Project_Roles")
    void sixSystemRolesAreAssignable(String code) {
        assertThat(TeamComposition.isAssignableProjectRole(code)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPERVISOR", "custom-role", ""})
    @DisplayName("ADMIN and non-system / unknown codes are not assignable")
    void adminAndUnknownRolesAreNotAssignable(String code) {
        assertThat(TeamComposition.isAssignableProjectRole(code)).isFalse();
    }

    @Test
    @DisplayName("a null role code is not assignable")
    void nullRoleIsNotAssignable() {
        assertThat(TeamComposition.isAssignableProjectRole(null)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"manager,MANAGER", "' Worker ',WORKER", "client,CLIENT"})
    @DisplayName("matching is case-insensitive on the trimmed code")
    void matchingIsCaseInsensitiveAndTrimmed(String input, String ignored) {
        assertThat(TeamComposition.isAssignableProjectRole(input)).isTrue();
    }

    // --- firstTeamCompositionViolation sub-order (Req 6.6) ---

    @Test
    @DisplayName("an assignable, active user has no violation")
    void assignableActiveUserHasNoViolation() {
        assertThat(TeamComposition.firstTeamCompositionViolation("MANAGER", true))
                .isEqualTo(Violation.NONE);
    }

    @Test
    @DisplayName("a non-assignable role is reported as ROLE_NOT_ASSIGNABLE (active user)")
    void nonAssignableActiveUserIsRoleNotAssignable() {
        assertThat(TeamComposition.firstTeamCompositionViolation("ADMIN", true))
                .isEqualTo(Violation.ROLE_NOT_ASSIGNABLE);
    }

    @Test
    @DisplayName("an assignable role with an inactive user is reported as USER_INACTIVE")
    void assignableInactiveUserIsUserInactive() {
        assertThat(TeamComposition.firstTeamCompositionViolation("WORKER", false))
                .isEqualTo(Violation.USER_INACTIVE);
    }

    @Test
    @DisplayName("role.not.assignable wins over user.inactive when both hold (lowest-numbered criterion)")
    void roleNotAssignableWinsOverInactive() {
        assertThat(TeamComposition.firstTeamCompositionViolation("ADMIN", false))
                .isEqualTo(Violation.ROLE_NOT_ASSIGNABLE);
    }
}
