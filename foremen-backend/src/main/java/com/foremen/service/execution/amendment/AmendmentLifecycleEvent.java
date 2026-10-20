package com.foremen.service.execution.amendment;

import java.util.List;

/**
 * FOR-06-09 (Requirement 7.3): an immutable, in-process application event carrying an
 * <em>amendment-level</em> lifecycle change that should raise an in-app notification through the
 * generic {@code NotificationService} (FOR-05-07). Published by {@code AmendmentSigningService} on
 * each amendment status transition it owns and consumed by {@code AmendmentNotificationEmitter} on
 * an {@code AFTER_COMMIT} {@code @TransactionalEventListener}, so emission runs only <em>after</em>
 * the transition has durably committed and a failure while emitting can never roll it back
 * (R7.6; consistent with the FOR-05-08 {@link com.foremen.service.document.DocumentNotificationEvent}
 * after-commit pattern).
 *
 * <p>This is an <b>in-process event discriminator, not a persisted classifier</b>: nothing in this
 * event is stored. Following the repo's {@code DocumentNotificationEvent} /
 * {@code OfferNegotiationNotificationEvent} convention it carries only ids / codes (never a loaded
 * entity), so it stays valid on the after-commit thread, and the recipient set is resolved at
 * publish time and handed in as {@link #recipientUserIds}; the emitter does no further lookup, it
 * simply maps the {@link #trigger} to its {@code notification.amendment.*} i18n key and creates one
 * notification per recipient deep-linking to the amendment on the Aneksy tab.
 *
 * <p>The three triggers mirror exactly the amendment-level transitions the reused FOR-05-08
 * {@code notification.document.*} notifications do not cover (R7.2 / R7.3): sending for signing,
 * reopening after a decline/void, and becoming effective.
 *
 * @param trigger          which amendment lifecycle change occurred (selects the
 *                         {@code notification.amendment.*} type and recipient semantics, R7.3)
 * @param amendmentId      the amendment whose status changed (identifies the amendment for the
 *                         deep-link)
 * @param projectId        the owning project id (the Aneksy-tab deep-link is scoped to it, R7.3)
 * @param seqNo            the amendment sequence number within the project (identifies the amendment
 *                         in the message alongside {@code projectId}, R7.5)
 * @param recipientUserIds the resolved recipient user ids for this trigger (amendment owner MANAGER +
 *                         project FINANCIER per the R7.3 table), de-duplicated by the publisher;
 *                         never {@code null}, may be empty (nothing to notify — still not an error)
 */
public record AmendmentLifecycleEvent(
        Trigger trigger,
        Long amendmentId,
        Long projectId,
        Integer seqNo,
        List<Long> recipientUserIds) {

    public AmendmentLifecycleEvent {
        recipientUserIds = recipientUserIds == null ? List.of() : List.copyOf(recipientUserIds);
    }

    /** The three amendment-lifecycle notification triggers of Requirement 7.3. */
    public enum Trigger {
        /** A MANAGER/FINANCIER sent the amendment for signing ({@code OPEN → SENT_FOR_SIGNING}). */
        SENT_FOR_SIGNING,
        /** The amendment returned to {@code OPEN} because its document was declined/voided. */
        REOPENED,
        /** The amendment became {@code EFFECTIVE} (deltas applied, end date shifted). */
        EFFECTIVE
    }
}
