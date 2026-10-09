package com.foremen.service.team;

import java.util.Set;

/**
 * Pure helper carrying the FOR-05-09 team-composition decisions that do not need any I/O: which role
 * codes are an {@code Assignable_Project_Role}, and the {@code role.not.assignable} / {@code user.inactive}
 * sub-order of the team-composition step (task 6.3, Requirement 6 criteria 1, 3, 6).
 *
 * <p><b>Assignable_Project_Role</b> (Glossary): one of the system role codes {@code MANAGER},
 * {@code FOREMAN}, {@code ESTIMATOR}, {@code WORKER}, {@code FINANCIER}, {@code CLIENT}. Every other
 * code — including {@code ADMIN} and any non-system (custom) role — is <em>not</em> assignable: a user
 * whose Company_Role is such a code is managed through the admin area, never attached as a project
 * member, and an assignment resolving to it is rejected with
 * {@code error.project.member.role.not.assignable} (Requirement 6 criterion 1).
 *
 * <p><b>Sub-order</b> (Requirement 6 criterion 6): within the team-composition step, when a single
 * assignment violates more than one of criterion 1 ({@code role.not.assignable}) and criterion 3
 * ({@code user.inactive}), the <em>lowest-numbered</em> criterion wins — so a user whose Company_Role
 * is not assignable is reported as {@code role.not.assignable} even when that same user is also
 * inactive. {@link #firstTeamCompositionViolation(String, boolean)} encodes exactly that precedence.
 *
 * <p>Role-code matching is case-insensitive on the trimmed code, consistent with
 * {@link TeamMemberOrdering}. These checks apply to ADMIN and non-ADMIN callers alike — there is no
 * ABAC bypass of the composition rules (Requirement 6 criterion 4). The class is pure: it holds no
 * state, performs no I/O, and is not instantiable.
 */
public final class TeamComposition {

    /** The six Assignable_Project_Role codes (Glossary). Any other code is not assignable. */
    private static final Set<String> ASSIGNABLE_ROLE_CODES =
            Set.of("MANAGER", "FOREMAN", "ESTIMATOR", "WORKER", "FINANCIER", "CLIENT");

    /**
     * The team-composition criteria of Requirement 6 that {@link #firstTeamCompositionViolation}
     * ranks, in their numbered order. {@link #NONE} means the resolved role is assignable and the
     * user is active, so the team-composition step passes.
     */
    public enum Violation {

        /** No violation — the role is assignable and the user is active. */
        NONE,

        /** Requirement 6 criterion 1 — the resolved role code is not an Assignable_Project_Role. */
        ROLE_NOT_ASSIGNABLE,

        /** Requirement 6 criterion 3 — the assigned user is an Inactive_User ({@code active == false}). */
        USER_INACTIVE
    }

    private TeamComposition() {
    }

    /**
     * Whether {@code roleCode} is an Assignable_Project_Role (Requirement 6 criterion 1). Matching is
     * case-insensitive on the trimmed code; a {@code null} or blank code is not assignable.
     */
    public static boolean isAssignableProjectRole(String roleCode) {
        if (roleCode == null) {
            return false;
        }
        return ASSIGNABLE_ROLE_CODES.contains(roleCode.trim().toUpperCase());
    }

    /**
     * Returns the first team-composition criterion a prospective assignment violates, applying the
     * Requirement 6 criterion 6 precedence (lowest-numbered criterion wins): criterion 1
     * ({@code role.not.assignable}) is reported before criterion 3 ({@code user.inactive}) when both
     * hold.
     *
     * @param resolvedRoleCode the resolved Project_Role code (the user's Company_Role, D2)
     * @param userActive       the assigned user's global {@code active} flag
     * @return {@link Violation#ROLE_NOT_ASSIGNABLE} when the role is not assignable (regardless of
     *         {@code userActive}); otherwise {@link Violation#USER_INACTIVE} when the user is
     *         inactive; otherwise {@link Violation#NONE}
     */
    public static Violation firstTeamCompositionViolation(String resolvedRoleCode, boolean userActive) {
        if (!isAssignableProjectRole(resolvedRoleCode)) {
            return Violation.ROLE_NOT_ASSIGNABLE; // criterion 1 — lowest-numbered, wins over criterion 3
        }
        if (!userActive) {
            return Violation.USER_INACTIVE;       // criterion 3
        }
        return Violation.NONE;
    }
}
