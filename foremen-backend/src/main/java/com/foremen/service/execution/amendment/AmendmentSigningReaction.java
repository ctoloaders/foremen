package com.foremen.service.execution.amendment;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.foremen.service.document.DocumentNotificationEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-06-09 (Requirements 2.1, 2.4, 7.6; design §{@code AmendmentSigningReaction}) — the bridge
 * between the FOR-05-08 signing engine and {@link AmendmentSigningService}. It is a second
 * {@code AFTER_COMMIT} {@link TransactionalEventListener} on the engine's own
 * {@link DocumentNotificationEvent}, so the amendment transition it triggers runs only <em>after</em>
 * the signing transition has durably committed — a failure inside the reaction can therefore never
 * roll the signing itself back (R2.4, R7.6). This mirrors the FOR-05-08
 * {@code DocumentNotificationEmitter} pattern and keeps the signing engine oblivious to amendments.
 *
 * <p>The linked {@code SignableDocument} is the single source of truth for signing progress (R2.1);
 * this reaction never reads or writes {@code DocumentSignature} rows — it simply observes the engine's
 * terminal transitions and maps them onto the amendment's own {@code AmendmentStatus} via the service:
 *
 * <table>
 *   <caption>Engine trigger → amendment reaction</caption>
 *   <tr><th>{@link DocumentNotificationEvent.Trigger}</th><th>Service call</th></tr>
 *   <tr><td>{@code DOCUMENT_FULLY_SIGNED}</td><td>{@link AmendmentSigningService#onDocumentFullySigned}</td></tr>
 *   <tr><td>{@code DOCUMENT_SIGNING_DECLINED}</td><td>{@link AmendmentSigningService#onDocumentReopened}</td></tr>
 *   <tr><td>{@code DOCUMENT_VOIDED}</td><td>{@link AmendmentSigningService#onDocumentReopened}</td></tr>
 *   <tr><td>{@code DOCUMENT_SENT_FOR_SIGNING} / {@code DOCUMENT_SIGNED_BY_PARTY}</td><td>ignored (no amendment transition)</td></tr>
 * </table>
 *
 * <p>It is a no-op for any document whose {@code documentTypeCode} is not {@code AMENDMENT}; the
 * service callbacks are themselves status-guarded and idempotent, so a document that is of the
 * {@code AMENDMENT} type but not actually linked from a {@code SENT_FOR_SIGNING} amendment is also a
 * no-op (R3.3). {@code fallbackExecution = true} lets the listener also run when the event is
 * published outside a transaction (unit tests), mirroring {@code DocumentNotificationEmitter}.
 */
@Component
@Slf4j
public class AmendmentSigningReaction {

    /** The FOR-05-08 {@code SignableDocumentType} code this reaction observes (R2.1). */
    static final String AMENDMENT_TYPE_CODE = "AMENDMENT";

    private final AmendmentSigningService signingService;

    public AmendmentSigningReaction(AmendmentSigningService signingService) {
        this.signingService = signingService;
    }

    /**
     * Reflects a committed {@code AMENDMENT}-document signing transition onto its amendment, best
     * effort. Runs after the signing transaction commits so it can never roll it back (R2.4, R7.6);
     * the whole dispatch is wrapped so any failure is logged and swallowed.
     *
     * @param event the document-signing notification event published by the FOR-05-08 engine
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDocumentSigning(DocumentNotificationEvent event) {
        if (event == null || !AMENDMENT_TYPE_CODE.equals(event.documentTypeCode())) {
            return; // not our document ⇒ no amendment reaction.
        }
        try {
            switch (event.trigger()) {
                case DOCUMENT_FULLY_SIGNED -> signingService.onDocumentFullySigned(event.documentId());
                case DOCUMENT_SIGNING_DECLINED, DOCUMENT_VOIDED ->
                        signingService.onDocumentReopened(event.documentId());
                default -> {
                    // DOCUMENT_SENT_FOR_SIGNING / DOCUMENT_SIGNED_BY_PARTY — no amendment transition.
                }
            }
        } catch (Exception e) {
            // R2.4 / R7.6: best-effort — log and swallow so the committed signing transition is never
            // rolled back by a failure in the amendment reaction.
            log.error("Amendment signing reaction failed for document {} (trigger {})",
                    event.documentId(), event.trigger(), e);
        }
    }
}
