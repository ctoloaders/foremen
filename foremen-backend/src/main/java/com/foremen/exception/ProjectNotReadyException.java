package com.foremen.exception;

import com.foremen.service.readiness.AreaContribution;
import lombok.Getter;

import java.util.List;

/**
 * FOR-05-12 (Requirement 4; design Decision D4) — raised by the activation readiness gate when a
 * {@code pre-activation → ACTIVE} transition is requested while project-wide readiness is below
 * 100% and the caller has no valid force. Throwing this aborts the activation and leaves the
 * project status unchanged (Requirement 2.1).
 *
 * <p>It carries the <em>not-ready</em> areas — the {@link AreaContribution}s whose
 * {@link com.foremen.service.readiness.ReadinessState#state() state} is not {@code DONE}
 * (Requirement 4.2/4.3) — so the advice ({@code ForemenControllerAdvice#handleProjectNotReady})
 * can build the {@code 409} not-ready breakdown, resolving the top-level message and each area
 * name / outstanding-item message to the request locale. Mirrors the structured, advice-handled
 * {@link WorkReportBatchItemException} precedent.
 */
@Getter
public class ProjectNotReadyException extends RuntimeException {

    /** The areas whose state is not {@code DONE} (the not-ready breakdown, Requirement 4.2/4.3). */
    private final List<AreaContribution> notReadyAreas;

    public ProjectNotReadyException(List<AreaContribution> notReadyAreas) {
        super("Project is not ready to be handed over to work");
        this.notReadyAreas = notReadyAreas;
    }
}
