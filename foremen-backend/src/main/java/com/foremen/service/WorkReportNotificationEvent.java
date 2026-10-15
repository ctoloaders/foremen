package com.foremen.service;

/**
 * FOR-06-01 (Requirement 9): an immutable application event carrying a work-report lifecycle change
 * that should raise an in-app notification. Published by {@link WorkReportService} on its committed
 * transitions (a non-FOREMAN submit, and the approver's approve / reject / request-changes) and
 * consumed by {@link WorkReportNotificationEmitter} on an {@code AFTER_COMMIT}
 * {@code @TransactionalEventListener}, so emission runs only <em>after</em> the transition has
 * durably committed and a failure while emitting can never roll it back (R9; design §4.4,
 * mirroring {@code OfferNegotiationNotificationEvent} / {@code DocumentNotificationEvent}).
 *
 * <p>Following the repo's event convention, the event carries only ids / codes (never a loaded
 * entity), so it stays valid on the after-commit thread. The <b>recipient semantics</b> are a
 * function of the {@link #trigger}: a {@link Trigger#SUBMITTED} fans out to every ACTIVE FOREMAN
 * member of the project (resolved by the emitter from {@code ProjectMemberDao}); the three decision
 * triggers ({@link Trigger#APPROVED} / {@link Trigger#REJECTED} / {@link Trigger#CHANGES_REQUESTED})
 * target the report's {@link #authorUserId}. The {@link #reason} is carried only for the two
 * reason-bearing decisions (reject / request-changes, R9.3/R9.4).
 *
 * <p><b>Fast path (R9.5).</b> A FOREMAN self-report is auto-approved on create and emits nothing, so
 * {@link WorkReportService#create} publishes <b>no</b> event on the fast path — this event is never
 * constructed for it, so the emitter inherently stays silent (R5.2).
 *
 * @param trigger      which lifecycle change occurred (selects the notification type + recipients)
 * @param reportId     the report whose status changed (the deep-link target id)
 * @param projectId    the owning project id (the Work Reporting deep-link is scoped to it, R9.1)
 * @param authorUserId the report author's user id (the recipient of the three decision triggers);
 *                     may be {@code null} when unresolved (nothing to notify — still not an error)
 * @param reason       the rejection / change reason carried by {@link Trigger#REJECTED} /
 *                     {@link Trigger#CHANGES_REQUESTED} (R9.3/R9.4); {@code null} otherwise
 */
public record WorkReportNotificationEvent(
        Trigger trigger,
        Long reportId,
        Long projectId,
        Long authorUserId,
        String reason) {

    /** The four work-report notification triggers of Requirement 9 (the fast path publishes none). */
    public enum Trigger {
        /** A non-FOREMAN author submitted a report ⇒ notify every ACTIVE FOREMAN member (R9.1). */
        SUBMITTED,
        /** An approver approved the report ⇒ notify the author (R9.2). */
        APPROVED,
        /** An approver rejected the report ⇒ notify the author, carrying the reason (R9.3). */
        REJECTED,
        /** An approver requested changes ⇒ notify the author, carrying the reason (R9.4). */
        CHANGES_REQUESTED
    }
}
