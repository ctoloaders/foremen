package com.foremen.service.document;

/**
 * FOR-05-08 (Requirements 4.4, 13.1): the payload for
 * {@code POST /api/signable-documents/{id}/decline} — the caller declining their own signature (an
 * {@code UPDATE}, R8.2). The caller's {@code DocumentSignature} moves to {@code DECLINED} with the
 * supplied {@link #reason}; a decline does NOT by itself void the document (R4.4).
 *
 * @param reason the decline reason recorded on the signature
 */
public record DeclineRequest(String reason) {
}
