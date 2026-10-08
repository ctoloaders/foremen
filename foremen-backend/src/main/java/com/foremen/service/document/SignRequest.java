package com.foremen.service.document;

/**
 * FOR-05-08 (Requirements 6.4, 13.1): the payload for {@code POST /api/signable-documents/{id}/sign}
 * — the caller completing their own {@code ONLINE} / {@code PODPIS_GOV_PL} signature through the
 * provider abstraction (an {@code UPDATE}, R8.2). The sealed evidence's integrity is verified against
 * the document's stored {@code contentHash} before the signature is marked {@code SIGNED} (R6.4).
 *
 * @param providerRef the provider session reference to complete, or {@code null} to initiate
 * @param signerUserId the signer user id, or {@code null} to resolve from the caller/own signature
 */
public record SignRequest(
        String providerRef,
        Long signerUserId) {
}
