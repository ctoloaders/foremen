package com.foremen.service.document;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.foremen.service.offer.NotificationService;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-05-08 (Requirement 11; design §Components {@code DocumentNotificationEmitter}): the
 * best-effort consumer that turns a {@link DocumentNotificationEvent} into one or more in-app
 * {@code Notification}s via the generic FOR-05-07 {@link NotificationService}. The module persists
 * / delivers nothing itself — it is purely a <b>consumer</b> of the generic notification extension
 * point (R11.1).
 *
 * <p>It wires the five Requirement 11.2 triggers, each notification carrying a <b>deep-link</b> to
 * the document in the project's signing tab ({@code /projects/{projectId}/documentSigning}, R11.3):
 *
 * <table>
 *   <caption>Requirement 11.2 notification map</caption>
 *   <tr><th>Trigger</th><th>Type (i18n key)</th><th>Recipient(s)</th></tr>
 *   <tr><td>{@code DOCUMENT_SENT_FOR_SIGNING}</td><td>{@link #TYPE_SENT_FOR_SIGNING}</td><td>each designated signer</td></tr>
 *   <tr><td>{@code DOCUMENT_SIGNED_BY_PARTY}</td><td>{@link #TYPE_SIGNED_BY_PARTY}</td><td>the document owner</td></tr>
 *   <tr><td>{@code DOCUMENT_FULLY_SIGNED}</td><td>{@link #TYPE_FULLY_SIGNED}</td><td>the owner + all signers</td></tr>
 *   <tr><td>{@code DOCUMENT_SIGNING_DECLINED}</td><td>{@link #TYPE_SIGNING_DECLINED}</td><td>the document owner</td></tr>
 *   <tr><td>{@code DOCUMENT_VOIDED}</td><td>{@link #TYPE_VOIDED}</td><td>all still-pending signers</td></tr>
 * </table>
 *
 * The concrete recipient set for a trigger is resolved by the publisher (which holds the loaded
 * document / owner / signature set inside the signing transaction) and carried on the event's
 * {@link DocumentNotificationEvent#recipientUserIds()}; this emitter does no further lookup. It
 * only maps the trigger to its {@code Notification_Type} i18n key and creates one notification per
 * recipient with the signing-tab deep-link.
 *
 * <h2>Best-effort emission (R11.1)</h2>
 * The listener fires on {@link TransactionPhase#AFTER_COMMIT}, so a notification is created only
 * <em>after</em> the signing transition has durably committed — a failure here therefore cannot
 * roll it back. {@code fallbackExecution = true} lets the listener also run when there is no active
 * transaction (e.g. unit tests that publish outside a {@code @Transactional} boundary). Every
 * recipient is wrapped individually so <b>any</b> exception (a missing recipient, a
 * {@code NotificationService} failure, …) is logged and swallowed — emission never propagates and
 * never blocks the triggering transition, and one failed recipient never starves the others
 * (mirroring {@code OfferNotificationEmitter} / {@code InvitationEmailDispatcher}).
 *
 * <p>No event is published for pure {@code DRAFT}-stage edits, so this emitter inherently emits
 * nothing for them (R11.5).
 *
 * <p>The {@code type} values are pure {@code Notification_Type} i18n keys resolved to a localized
 * title on the frontend (R12.2); no backend {@code messages} entry is required for them.
 */
@Component
@Slf4j
public class DocumentNotificationEmitter {

    /** {@code Notification_Type} i18n key: a document was sent for signing (to each signer, R11.2). */
    static final String TYPE_SENT_FOR_SIGNING = "notification.document.sentForSigning";

    /** {@code Notification_Type} i18n key: a signer completed their signature (to the owner, R11.2). */
    static final String TYPE_SIGNED_BY_PARTY = "notification.document.signedByParty";

    /** {@code Notification_Type} i18n key: the document is now fully signed (to owner + signers, R11.2). */
    static final String TYPE_FULLY_SIGNED = "notification.document.fullySigned";

    /** {@code Notification_Type} i18n key: a signer declined (to the owner, R11.2). */
    static final String TYPE_SIGNING_DECLINED = "notification.document.signingDeclined";

    /** {@code Notification_Type} i18n key: a pending document was voided (to still-pending signers, R11.2). */
    static final String TYPE_VOIDED = "notification.document.voided";

    /** The signing-tab key the deep-link targets ({@code /projects/{projectId}/documentSigning}, R11.3). */
    private static final String SIGNING_TAB = "documentSigning";

    private final NotificationService notificationService;

    public DocumentNotificationEmitter(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * Emits the in-app notification(s) for a document-signing trigger, best effort. Runs after the
     * signing transaction commits so it can never roll it back (R11.1); each recipient's emission is
     * wrapped so any failure is logged and swallowed.
     *
     * @param event the document-signing notification event published by the signing services
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDocumentSigning(DocumentNotificationEvent event) {
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

    /** Creates one notification for a single recipient, logging and swallowing any failure (R11.1). */
    private void emitTo(Long recipientUserId, String type, String body, String deepLink,
                        DocumentNotificationEvent event) {
        if (recipientUserId == null) {
            return;
        }
        try {
            notificationService.create(recipientUserId, type, body, deepLink);
        } catch (Exception e) {
            // R11.1: log-only on failure — never propagate, never block the committed signing
            // transition, and never starve the remaining recipients.
            log.error("Failed to emit document-signing notification (type {}) for document {} "
                            + "to user {} (trigger {})",
                    type, event.documentId(), recipientUserId, event.trigger(), e);
        }
    }

    /** Maps a trigger to its {@code Notification_Type} i18n key (R11.2). */
    private static String typeFor(DocumentNotificationEvent.Trigger trigger) {
        if (trigger == null) {
            return null;
        }
        return switch (trigger) {
            case DOCUMENT_SENT_FOR_SIGNING -> TYPE_SENT_FOR_SIGNING;
            case DOCUMENT_SIGNED_BY_PARTY -> TYPE_SIGNED_BY_PARTY;
            case DOCUMENT_FULLY_SIGNED -> TYPE_FULLY_SIGNED;
            case DOCUMENT_SIGNING_DECLINED -> TYPE_SIGNING_DECLINED;
            case DOCUMENT_VOIDED -> TYPE_VOIDED;
        };
    }

    /**
     * The signing-tab deep-link for a project ({@code /projects/{projectId}/documentSigning}, R11.3):
     * the project-workspace route whose {@code :tab} slug is the {@code documentSigning} tab registered
     * in {@code workspaceTabs.ts}. Returns {@code null} when the project id is unknown (a
     * non-interactive notification, consistent with {@code NotificationService.create}'s nullable
     * deep-link).
     */
    private static String deepLink(Long projectId) {
        return projectId == null ? null : "/projects/" + projectId + "/" + SIGNING_TAB;
    }

    /**
     * Identifies the document in the notification body — its type code + title/id (R11.3). The
     * localized title of the notification itself is resolved from the {@code type} i18n key on the
     * frontend; this body carries the document identity so the message names the concrete document.
     * Returns {@code null} when nothing identifying is known (the type key alone then stands).
     */
    private static String messageBody(DocumentNotificationEvent event) {
        List<String> parts = new java.util.ArrayList<>(3);
        if (event.documentTypeCode() != null && !event.documentTypeCode().isBlank()) {
            parts.add(event.documentTypeCode());
        }
        if (event.title() != null && !event.title().isBlank()) {
            parts.add(event.title());
        } else if (event.documentId() != null) {
            parts.add("#" + event.documentId());
        }
        return parts.isEmpty() ? null : String.join(" — ", parts);
    }
}
