package com.foremen.service.execution.amendment;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.foremen.service.offer.NotificationService;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-06-09 (Requirements 7.1, 7.3, 7.4, 7.5, 7.6; design §{@code AmendmentNotificationEmitter}): the
 * best-effort consumer that turns an {@link AmendmentLifecycleEvent} into one or more in-app
 * {@code Notification}s via the generic FOR-05-07 {@link NotificationService}. It mirrors the
 * FOR-05-08 {@code DocumentNotificationEmitter} pattern and persists / delivers nothing itself — it
 * is purely a <b>consumer</b> of the generic notification extension point.
 *
 * <p>It wires the three amendment-level lifecycle triggers of Requirement 7.3, each notification
 * carrying a <b>deep-link</b> to the amendment on the project's Aneksy tab
 * ({@code /projects/{projectId}/amendments}, R7.3 / R7.5):
 *
 * <table>
 *   <caption>Requirement 7.3 amendment-notification map</caption>
 *   <tr><th>Trigger</th><th>Type (i18n key)</th><th>Recipient(s)</th></tr>
 *   <tr><td>{@code SENT_FOR_SIGNING}</td><td>{@link #TYPE_SENT_FOR_SIGNING}</td><td>amendment owner (MANAGER) + project FINANCIER</td></tr>
 *   <tr><td>{@code REOPENED}</td><td>{@link #TYPE_REOPENED}</td><td>amendment owner + project FINANCIER</td></tr>
 *   <tr><td>{@code EFFECTIVE}</td><td>{@link #TYPE_EFFECTIVE}</td><td>amendment owner + project FINANCIER</td></tr>
 * </table>
 *
 * <p>The concrete recipient set for a trigger is resolved by the publisher
 * ({@code AmendmentSigningService}, which holds the loaded amendment + project inside the signing
 * transaction) and carried on the event's {@link AmendmentLifecycleEvent#recipientUserIds()}; this
 * emitter does no further lookup. It only maps the trigger to its {@code Notification_Type} i18n key
 * and creates one notification per recipient with the Aneksy-tab deep-link.
 *
 * <p><b>Emits only {@code notification.amendment.*}.</b> The CLIENT-facing signing ceremony (sent /
 * fully-signed / declined / voided) is covered by the reused FOR-05-08 {@code notification.document.*}
 * types; this emitter never emits a {@code document.*} duplicate and never a second CLIENT-facing
 * notification for the same event (R7.2, R7.4).
 *
 * <h2>Best-effort emission (R7.1, R7.6)</h2>
 * The listener fires on {@link TransactionPhase#AFTER_COMMIT}, so a notification is created only
 * <em>after</em> the amendment transition has durably committed — a failure here therefore cannot
 * roll it back. {@code fallbackExecution = true} lets the listener also run when there is no active
 * transaction (e.g. unit tests that publish outside a {@code @Transactional} boundary). Every
 * recipient is wrapped individually so <b>any</b> exception (a missing recipient, a
 * {@code NotificationService} failure, …) is logged and swallowed — emission never propagates and
 * never blocks the triggering transition, and one failed recipient never starves the others
 * (mirroring {@code DocumentNotificationEmitter}).
 *
 * <p>The {@code type} values are pure {@code Notification_Type} i18n keys resolved to a localized
 * title on the frontend; no backend {@code messages} entry is required for them.
 */
@Component
@Slf4j
public class AmendmentNotificationEmitter {

    /** {@code Notification_Type} i18n key: an amendment was sent for signing ({@code OPEN → SENT_FOR_SIGNING}, R7.3). */
    static final String TYPE_SENT_FOR_SIGNING = "notification.amendment.sentForSigning";

    /** {@code Notification_Type} i18n key: an amendment reopened after a decline/void ({@code → OPEN}, R7.3). */
    static final String TYPE_REOPENED = "notification.amendment.reopened";

    /** {@code Notification_Type} i18n key: an amendment became effective ({@code → EFFECTIVE}, R7.3). */
    static final String TYPE_EFFECTIVE = "notification.amendment.effective";

    /** The Aneksy-tab key the deep-link targets ({@code /projects/{projectId}/amendments}, R7.3 / R7.5). */
    private static final String AMENDMENTS_TAB = "amendments";

    private final NotificationService notificationService;

    public AmendmentNotificationEmitter(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * Emits the in-app notification(s) for an amendment-lifecycle trigger, best effort. Runs after the
     * amendment transition commits so it can never roll it back (R7.1, R7.6); each recipient's emission
     * is wrapped so any failure is logged and swallowed.
     *
     * @param event the amendment-lifecycle notification event published by {@code AmendmentSigningService}
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAmendmentLifecycle(AmendmentLifecycleEvent event) {
        if (event == null) {
            return;
        }
        String type = typeFor(event.trigger());
        if (type == null) {
            return;
        }
        String deepLink = deepLink(event.projectId());
        String body = messageBody(event);
        for (Long recipientUserId : event.recipientUserIds()) {
            emitTo(recipientUserId, type, body, deepLink, event);
        }
    }

    /** Creates one notification for a single recipient, logging and swallowing any failure (R7.1, R7.6). */
    private void emitTo(Long recipientUserId, String type, String body, String deepLink,
                        AmendmentLifecycleEvent event) {
        if (recipientUserId == null) {
            return;
        }
        try {
            notificationService.create(recipientUserId, type, body, deepLink);
        } catch (Exception e) {
            // R7.1 / R7.6: log-only on failure — never propagate, never block the committed amendment
            // transition, and never starve the remaining recipients.
            log.error("Failed to emit amendment-lifecycle notification (type {}) for amendment {} "
                            + "to user {} (trigger {})",
                    type, event.amendmentId(), recipientUserId, event.trigger(), e);
        }
    }

    /** Maps a trigger to its {@code notification.amendment.*} i18n key (R7.3). */
    private static String typeFor(AmendmentLifecycleEvent.Trigger trigger) {
        if (trigger == null) {
            return null;
        }
        return switch (trigger) {
            case SENT_FOR_SIGNING -> TYPE_SENT_FOR_SIGNING;
            case REOPENED -> TYPE_REOPENED;
            case EFFECTIVE -> TYPE_EFFECTIVE;
        };
    }

    /**
     * The Aneksy-tab deep-link for a project ({@code /projects/{projectId}/amendments}, R7.3 / R7.5):
     * the project-workspace route whose {@code :tab} slug is the {@code amendments} tab. Returns
     * {@code null} when the project id is unknown (a non-interactive notification, consistent with
     * {@code NotificationService.create}'s nullable deep-link).
     */
    private static String deepLink(Long projectId) {
        return projectId == null ? null : "/projects/" + projectId + "/" + AMENDMENTS_TAB;
    }

    /**
     * Identifies the amendment in the notification body by project + {@code seqNo} (R7.5). The
     * localized title of the notification itself is resolved from the {@code type} i18n key on the
     * frontend; this body carries the amendment identity so the message names the concrete amendment.
     * Returns {@code null} when nothing identifying is known (the type key alone then stands).
     */
    private static String messageBody(AmendmentLifecycleEvent event) {
        java.util.List<String> parts = new java.util.ArrayList<>(2);
        if (event.projectId() != null) {
            parts.add("project #" + event.projectId());
        }
        if (event.seqNo() != null) {
            parts.add("amendment #" + event.seqNo());
        }
        return parts.isEmpty() ? null : String.join(" — ", parts);
    }
}
