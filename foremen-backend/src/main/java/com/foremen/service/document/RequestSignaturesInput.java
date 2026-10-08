package com.foremen.service.document;

import java.util.List;

import com.foremen.dao.model.SignatureLevel;
import com.foremen.dao.model.SignatureMethod;

/**
 * FOR-05-08 (Requirements 4.2, 1.5, 13.1): the payload for
 * {@code POST /api/signable-documents/{id}/request-signatures}. It supplies the chosen signing
 * {@link #method}, the optional eIDAS {@link #level} (defaulting to {@code AdES} at the service
 * layer when {@code null}), and the signer set ({@code >= 1}, R4.2). Accepting it freezes the
 * immutable PDF, sets {@code contentHash}, creates one {@code PENDING} signature per signer, and
 * moves the document to {@code PENDING_SIGNATURES}.
 *
 * @param method  the signing method for the created signatures (required)
 * @param level   the eIDAS level override, or {@code null} to default to {@code AdES}
 * @param signers the designated signers (at least one, R4.2)
 */
public record RequestSignaturesInput(
        SignatureMethod method,
        SignatureLevel level,
        List<SignerInput> signers) {

    /**
     * One designated signer in a request-signatures call. A signer may be identified by user, by
     * role, or both (R4.3); a {@code CLIENT} role resolves to the project's CLIENT member(s)
     * (sign-any, decision 8).
     *
     * @param signerUserId the explicit signer user id, or {@code null} to resolve by role
     * @param signerRole   the signer role (e.g. {@code CLIENT}), or {@code null}
     */
    public record SignerInput(Long signerUserId, String signerRole) {
    }
}
