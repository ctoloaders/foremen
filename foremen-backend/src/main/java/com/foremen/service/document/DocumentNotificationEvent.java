package com.foremen.service.document;

import java.util.List;

/**
 * FOR-05-08 (Requirement 11): an immutable application event carrying a document-signing lifecycle
 * change that should raise an in-app notification. Published by the signing services (the
 * {@code requestSignatures} / {@code void} paths of {@code SignableDocumentService} and the
 * per-method completion / decline / full-sign paths of {@code SignatureService}) and consumed by
 * {@link DocumentNotificationEmitter} on an {@code AFTER_COMMIT}
 * {@code @TransactionalEventListener}, so emission runs only <em>after</em> the signing transition
 * has durably committed and a failure while emitting can never roll it back (R11.1; design
 * §DocumentNotificationEmitter, FOR-05-07 §Key decision 7).
 *
 * <p>Following the repo's {@code OfferNegotiationNotificationEvent} / {@code InvitationEmailEvent}
 * convention, the event carries only ids / codes / paths (never a loaded entity), so it stays valid
 * on the after-commit thread. The <b>recipient set is resolved at publish time</b> by the publisher
 * — which already holds the loaded document, its owner, and its signature set inside the signing
 * transaction — and handed in as {@link #recipientUserIds}; the emitter does no further lookup, it
 * simply maps the {@link #trigger} to its {@code Notification_Type} i18n key and creates one
 * notification per recipient with a deep-link to the document in the signing tab (R11.2, R11.3).
 *
 * <p>No event is published for pure {@code DRAFT}-stage edits (create / generate / regenerate /
 * saveBody); those paths never construct this event, so no notification is ever emitted for them
 * (R11.5).
 *
 * @param trigger          which lifecycle change occurred (selects the notification type + the
 *                         recipient semantics, R11.2)
 * @param documentId       the document whose signing changed (used for the deep-link and to identify
 *                         the document in the message)
 * @param projectId        the owning project id (the signing-tab deep-link is scoped to it, R11.3)
 * @param documentTypeCode the {@code SignableDocumentType} code of the document (identifies the
 *                         document in the message, R11.3); may be {@code null}
 * @param title            the document title, if any (identifies the document in the message, R11.3);
 *                         may be {@code null}
 * @param recipientUserIds the resolved recipient user ids for this trigger (each designated signer /
 *                         the owner / owner + all signers / still-pending signers per the R11.2
 *                         table), de-duplicated by the publisher; never {@code null}, may be empty
 *                         (nothing to notify — still not an error)
 */
public record DocumentNotificationEvent(
        Trigger trigger,
        Long documentId,
        Long projectId,
        String documentTypeCode,
        String title,
        List<Long> recipientUserIds) {

    public DocumentNotificationEvent {
        recipientUserIds = recipientUserIds == null ? List.of() : List.copyOf(recipientUserIds);
    }

    /** The five document-signing notification triggers of Requirement 11.2. */
    public enum Trigger {
        /** The manager requested signatures ⇒ notify each designated signer (R11.2). */
        DOCUMENT_SENT_FOR_SIGNING,
        /** A signer completed their signature ⇒ notify the document owner (R11.2). */
        DOCUMENT_SIGNED_BY_PARTY,
        /** The last signature completed (document → {@code SIGNED}) ⇒ notify the owner + all signers (R11.2). */
        DOCUMENT_FULLY_SIGNED,
        /** A signer declined ⇒ notify the document owner (R11.2). */
        DOCUMENT_SIGNING_DECLINED,
        /** The manager voided a {@code PENDING_SIGNATURES} document ⇒ notify all still-pending signers (R11.2). */
        DOCUMENT_VOIDED
    }
}
