package com.foremen.service.team;

import com.foremen.exception.ForemenApiException;

import java.util.ArrayList;
import java.util.List;

/**
 * FOR-05-09 (task 6.1, Requirements 3.7, 10.4) — the single, deterministic ordered checklist that
 * every mutating Team_API path funnels through so that the <strong>first failing check in the
 * canonical order determines the response</strong> and nothing later executes (design §"Canonical
 * rejection order", Property 11).
 *
 * <p><b>Why a central checklist.</b> Assign, Attribute_Update (worker type / tags / status),
 * deactivate / reactivate, remove, and the registration / worker / creation flows all share the
 * same precedence of validation. Centralizing it here — rather than letting each flow throw by
 * exception type and relying on the order the code happens to run — guarantees the precedence is a
 * <em>deterministic sequence</em>: a locked project hides a would-be duplicate, a missing mandatory
 * field hides a missing project, and so on (design: "enforced as a deterministic sequence, not
 * short-circuit-by-exception-type"). On any failure the checklist returns the first tripped check's
 * error and leaves every {@code project_members} row unchanged, because no mutation happens inside a
 * check — the caller mutates only after {@link #run(TeamRejectionContext)} returns normally.
 *
 * <p><b>The canonical order</b> (design §"Canonical rejection order"; Requirement 3 criterion 7).
 * Steps 1–2 (401 missing/expired token, 403 missing {@code PROJECT_MEMBERS} operation) are enforced
 * <em>upstream</em> by the security filter and {@code PermissionInterceptor} before the controller
 * body runs, so they are not re-checked here; this checklist owns steps 3–9:
 * <ol start="3">
 *   <li><b>Mandatory fields</b> — 400 for a missing / invalid mandatory field, including
 *       {@code error.project.member.role.mismatch} and {@code error.project.member.tag.invalid}
 *       ({@link Step#MANDATORY_FIELDS}).</li>
 *   <li><b>Project existence / access</b> — 404 {@code error.entity.not.found} when the project is
 *       missing or not an Accessible_Project, with the ADMIN bypass ({@link Step#PROJECT_ACCESS}).</li>
 *   <li><b>Lifecycle lock</b> — 409 {@code error.project.team.locked} on a Locked_Status project
 *       ({@link Step#LIFECYCLE_LOCK}; filled by task 6.3).</li>
 *   <li><b>Member existence / duplicate</b> — 404 {@code error.project.member.not.found}
 *       (update / deactivate / remove) or 409 {@code error.project.member.duplicate} (assign)
 *       ({@link Step#MEMBER_EXISTENCE}).</li>
 *   <li><b>User existence</b> — 404 {@code error.entity.not.found} for a non-existent user on assign
 *       ({@link Step#USER_EXISTENCE}).</li>
 *   <li><b>Team-composition sub-order</b> — 400 in the sub-order
 *       {@code role.not.allowed.at.creation} (creation only) &rarr; {@code role.not.assignable}
 *       &rarr; {@code user.inactive} &rarr; {@code worker.type.not.allowed} &rarr;
 *       {@code worker.type.invalid} ({@link Step#TEAM_COMPOSITION}; filled by task 6.3).</li>
 *   <li><b>Last ACTIVE MANAGER / CLIENT</b> — 409 {@code error.project.member.last.manager} /
 *       {@code error.project.member.last.client} ({@link Step#LAST_ACTIVE_GUARD}; filled by task
 *       6.3).</li>
 * </ol>
 *
 * <p><b>Extension points.</b> This task (6.1) wires the steps whose checks already have helpers —
 * mandatory fields ({@code role.mismatch} via {@code RoleDao}, {@code tag.invalid} via
 * {@link com.foremen.util.TagNormalizer}), project existence / access (the
 * {@link com.foremen.service.ProjectScopedService} contract), member existence / duplicate, and user
 * existence. The remaining steps — {@link Step#LIFECYCLE_LOCK}, {@link Step#TEAM_COMPOSITION}, and
 * {@link Step#LAST_ACTIVE_GUARD} — are left as clearly-marked, named slots that task 6.3 fills with
 * the composition checks (role assignability, inactive user, lifecycle lock, last-ACTIVE guards). A
 * slot with no supplied check is a no-op, so the ordering scaffold is correct and compilable now and
 * each check "slots in" at its named position without reordering the rest. A {@link TeamCheck} is a
 * single named check that either returns normally (passes) or throws a {@link ForemenApiException}
 * carrying the first tripped error.
 *
 * <p>This class is a pure sequencer: it holds no state, performs no I/O of its own, and runs the
 * supplied checks strictly in {@link Step} order, stopping at the first that throws. It is not
 * instantiable.
 */
public final class TeamRejectionChecklist {

    /**
     * The ordered steps of the canonical rejection sequence (design §"Canonical rejection order",
     * Requirement 3 criterion 7). Steps 1–2 (token, permission) are upstream; declaration order here
     * is the enforced order of steps 3–9. {@link Enum#ordinal()} is the sequence position.
     */
    public enum Step {

        /** 3 — 400 missing / invalid mandatory field, incl. {@code role.mismatch} + {@code tag.invalid}. */
        MANDATORY_FIELDS,

        /** 4 — 404 {@code error.entity.not.found}: project missing or not an Accessible_Project (ADMIN bypass). */
        PROJECT_ACCESS,

        /** 5 — 409 {@code error.project.team.locked}: project in a Locked_Status (task 6.3). */
        LIFECYCLE_LOCK,

        /** 6 — 404 {@code error.project.member.not.found} (update/remove) / 409 {@code error.project.member.duplicate} (assign). */
        MEMBER_EXISTENCE,

        /** 7 — 404 {@code error.entity.not.found}: non-existent user (assign). */
        USER_EXISTENCE,

        /** 8 — 400 team-composition sub-order (role.not.allowed.at.creation / role.not.assignable / user.inactive / worker.type.*) (task 6.3). */
        TEAM_COMPOSITION,

        /** 9 — 409 {@code error.project.member.last.manager} / {@code error.project.member.last.client} (task 6.3). */
        LAST_ACTIVE_GUARD
    }

    /** A single named check: returns normally when it passes, throws when it trips. */
    @FunctionalInterface
    public interface TeamCheck {

        /**
         * Runs the check.
         *
         * @throws ForemenApiException with the HTTP status and message code of the tripped check
         */
        void check();
    }

    private TeamRejectionChecklist() {
    }

    /**
     * Runs the canonical rejection checklist in {@link Step} order and returns normally only when
     * every supplied check passes. The <em>first</em> check that throws a {@link ForemenApiException}
     * stops the sequence immediately, so later steps never run (Requirement 3 criterion 7,
     * Requirement 10 criterion 4) — the returned/propagated error is always the first tripped
     * check's. A {@link Step} whose context supplies no check (a slot task 6.3 has not filled, or one
     * that does not apply to the current operation) is skipped as a no-op; it never changes the
     * precedence of the steps that are present.
     *
     * <p>No check mutates persistent state, so a failed run leaves every row unchanged and the caller
     * performs its mutation only after this method returns normally.
     *
     * @param context the per-operation checklist carrying the ordered checks (a {@code null} check
     *                 for a step means "nothing to verify at this step")
     * @throws ForemenApiException the first tripped check's error
     */
    public static void run(TeamRejectionContext context) {
        for (TeamCheck check : context.orderedChecks()) {
            if (check != null) {
                check.check();
            }
        }
    }

    /**
     * The per-operation checklist: one optional {@link TeamCheck} per {@link Step}, assembled by a
     * {@link Builder} so each flow (assign, update, remove, creation) supplies only the checks that
     * apply to it and leaves the rest {@code null}. The order of {@link #orderedChecks()} is always
     * the canonical {@link Step} order regardless of the order the builder set them in.
     */
    public static final class TeamRejectionContext {

        private final TeamCheck[] checks;

        private TeamRejectionContext(TeamCheck[] checks) {
            this.checks = checks;
        }

        /** The checks in strict canonical {@link Step} order (a {@code null} entry is a skipped step). */
        List<TeamCheck> orderedChecks() {
            List<TeamCheck> ordered = new ArrayList<>(checks.length);
            for (Step step : Step.values()) {
                ordered.add(checks[step.ordinal()]);
            }
            return ordered;
        }

        /** Starts a new, empty checklist with every step unset. */
        public static Builder builder() {
            return new Builder();
        }
    }

    /**
     * Fluent assembler for a {@link TeamRejectionContext}. A flow sets only the steps it needs; the
     * steps left unset default to a no-op. Setting a step twice replaces the earlier check (the last
     * wins), which keeps wiring from different layers composable.
     */
    public static final class Builder {

        private final TeamCheck[] checks = new TeamCheck[Step.values().length];

        private Builder() {
        }

        /** Registers {@code check} at {@code step}; a {@code null} check clears the step back to a no-op. */
        public Builder at(Step step, TeamCheck check) {
            checks[step.ordinal()] = check;
            return this;
        }

        /** The step-3 mandatory-fields check (incl. {@code role.mismatch} + {@code tag.invalid}). */
        public Builder mandatoryFields(TeamCheck check) {
            return at(Step.MANDATORY_FIELDS, check);
        }

        /** The step-4 project existence / access check (404, ADMIN bypass). */
        public Builder projectAccess(TeamCheck check) {
            return at(Step.PROJECT_ACCESS, check);
        }

        /** The step-5 lifecycle-lock check (409 {@code error.project.team.locked}) — task 6.3 slot. */
        public Builder lifecycleLock(TeamCheck check) {
            return at(Step.LIFECYCLE_LOCK, check);
        }

        /** The step-6 member existence / duplicate check (404 not-found / 409 duplicate). */
        public Builder memberExistence(TeamCheck check) {
            return at(Step.MEMBER_EXISTENCE, check);
        }

        /** The step-7 user-existence check (404 for a non-existent user on assign). */
        public Builder userExistence(TeamCheck check) {
            return at(Step.USER_EXISTENCE, check);
        }

        /** The step-8 team-composition sub-order check (400 sub-order) — task 6.3 slot. */
        public Builder teamComposition(TeamCheck check) {
            return at(Step.TEAM_COMPOSITION, check);
        }

        /** The step-9 last-ACTIVE MANAGER / CLIENT guard (409) — task 6.3 slot. */
        public Builder lastActiveGuard(TeamCheck check) {
            return at(Step.LAST_ACTIVE_GUARD, check);
        }

        /** Builds the immutable, step-ordered checklist. */
        public TeamRejectionContext build() {
            return new TeamRejectionContext(checks.clone());
        }
    }
}
