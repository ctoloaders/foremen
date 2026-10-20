package com.foremen.service.model;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FOR-06-09 (Requirements 2.5, 6.2; design §Read-model additions) — the service-layer projection of
 * a {@code SENT_FOR_SIGNING} amendment's signing progress, read <b>through</b> the FOR-05-08 engine
 * ({@code SignableDocumentService.progress(signableDocumentId)}) rather than stored. The amendment
 * never maintains a parallel signature set (R2.1); this is a read-only snapshot of the linked
 * document's {@code SigningProgress} so the Aneksy tab can render signed/total + the outstanding
 * signers without a second lifecycle (R2.5).
 *
 * <p>Populated only when the amendment has a linked {@code signableDocument}; otherwise the extended
 * model leaves {@code signingProgress} {@code null}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AmendmentSigningProgressModel {

    /** How many of the linked document's signatures are {@code SIGNED} ({@code 0..totalCount}). */
    private int signedCount;

    /** The total number of designated signatures on the linked document. */
    private int totalCount;

    /** The still-outstanding (not-{@code SIGNED}) signers; size is {@code totalCount - signedCount}. */
    private List<OutstandingSigner> outstanding = new ArrayList<>();

    /**
     * A lightweight reference to an outstanding signer, mirroring the FOR-05-08
     * {@code SigningProgressDto.SignerRefDto} identity facts — no cost/estimate/margin data.
     *
     * @param signerUserId the explicit signer user id, or {@code null} when resolved by role
     * @param signerRole   the signer role (e.g. {@code CLIENT}), or {@code null}
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OutstandingSigner {
        private Long signerUserId;
        private String signerRole;
    }
}
