package com.foremen.service.signing;

import com.foremen.dao.model.DocumentStatus;

/**
 * FOR-05-08 (Requirement 1.3): the lifecycle <b>commands</b> that drive a
 * {@code SignableDocument} between {@link DocumentStatus} states via
 * {@link DocumentStatusMachine#transition}.
 *
 * <p>Only two commands move a document between statuses:
 *
 * <ul>
 *   <li>{@link #REQUEST_SIGNATURES} — {@code DRAFT → PENDING_SIGNATURES} (freezes the immutable
 *       signing artifact; design §Signing lifecycle).</li>
 *   <li>{@link #VOID} — {@code DRAFT → VOID} and {@code PENDING_SIGNATURES → VOID} (cancel).</li>
 * </ul>
 *
 * <p>There is deliberately <b>no</b> command that targets {@link DocumentStatus#SIGNED}: a document
 * becomes {@code SIGNED} only as a derived consequence of every {@code DocumentSignature} being
 * {@code SIGNED} ({@code recomputeSignedState}), never through this state machine (Requirements 1.4,
 * 6.6; design key decision 4). Terminal states ({@code SIGNED}, {@code VOID}) have no exit command.
 */
public enum DocumentAction {

    /** Request signatures: {@code DRAFT → PENDING_SIGNATURES}. */
    REQUEST_SIGNATURES,

    /** Void/cancel: {@code DRAFT → VOID} and {@code PENDING_SIGNATURES → VOID}. */
    VOID
}
