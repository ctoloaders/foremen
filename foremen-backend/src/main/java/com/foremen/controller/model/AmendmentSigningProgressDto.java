package com.foremen.controller.model;

import java.util.List;

/**
 * FOR-06-09 (Requirements 2.5, 6.2; design §Read-model additions) — the client-reachable signing
 * progress of a {@code SENT_FOR_SIGNING} amendment, read <b>through</b> the FOR-05-08 engine
 * ({@code SignableDocumentService.progress}) and projected onto the amendment read model so the
 * Aneksy tab renders signed/total + the outstanding signers without a second signing lifecycle
 * (R2.5). Never stored — the linked document is the single source of truth (R2.1).
 *
 * <p>Carries only signer identity facts (no cost/estimate/margin data), mirroring the FOR-05-08
 * {@code SigningProgressDto} shape.
 *
 * @param signedCount how many of the linked document's signatures are {@code SIGNED}
 * @param totalCount  the total number of designated signatures
 * @param outstanding the still-outstanding (not-{@code SIGNED}) signers
 */
public record AmendmentSigningProgressDto(
        int signedCount,
        int totalCount,
        List<OutstandingSignerDto> outstanding) {

    /**
     * A lightweight reference to an outstanding signer.
     *
     * @param signerUserId the explicit signer user id, or {@code null} when resolved by role
     * @param signerRole   the signer role (e.g. {@code CLIENT}), or {@code null}
     */
    public record OutstandingSignerDto(Long signerUserId, String signerRole) {
    }
}
