package com.foremen.service.document;

import java.util.List;

import com.foremen.dao.model.SignatureLevel;
import com.foremen.dao.model.SignatureMethod;

/**
 * FOR-05-08 (signer management on a sent document): the payload for
 * {@code PUT /api/signable-documents/{id}/signers}. It edits the designated-signer set of a document
 * that is already {@code PENDING_SIGNATURES}, without re-freezing the artifact and without leaving
 * {@code PENDING_SIGNATURES}:
 *
 * <ul>
 *   <li>{@link #add} — new signers to designate, each with its own method / optional level. A user
 *       already designated on the document is skipped (idempotent add).</li>
 *   <li>{@link #removeSignatureIds} — the ids of still-{@code PENDING} signatures to hard-delete
 *       (R: remove those who did not sign yet). A {@code SIGNED} / {@code DECLINED} signature is
 *       never removed.</li>
 *   <li>{@link #changes} — method / level changes to apply to existing {@code PENDING} signatures
 *       (the "the signer will complete on their own with method X" flow — e.g. switch an on-site
 *       {@code TABLET_INITIALS} line to {@code TABLET_INITIALS} for a different device, or to
 *       {@code ONLINE} / {@code PODPIS_GOV_PL} / {@code PRINT}).</li>
 * </ul>
 *
 * <p>All three sections are optional; an absent / empty section is a no-op for that dimension. The
 * document stays {@code PENDING_SIGNATURES} and the derived {@code SIGNED} state is recomputed after
 * the edit (removing the last outstanding signer of an otherwise-signed set can promote it).
 *
 * @param add               signers to add (each with a method + optional level); may be {@code null}
 * @param removeSignatureIds ids of {@code PENDING} signatures to hard-delete; may be {@code null}
 * @param changes           method/level changes to existing {@code PENDING} signatures; may be {@code null}
 */
public record EditSignersInput(
        List<AddSigner> add,
        List<Long> removeSignatureIds,
        List<SignatureChange> changes) {

    /**
     * One signer to add to a {@code PENDING_SIGNATURES} document.
     *
     * @param signerUserId the explicit signer user id (required for an added signer — the editor
     *                     picks concrete project members, never an abstract role)
     * @param method       the signing method for the new signature (required)
     * @param level        the eIDAS level override, or {@code null} to default to {@code AdES}
     */
    public record AddSigner(Long signerUserId, SignatureMethod method, SignatureLevel level) {
    }

    /**
     * A method / level change to one existing {@code PENDING} signature.
     *
     * @param signatureId the target {@code PENDING} signature id (required)
     * @param method      the new signing method (required)
     * @param level       the new eIDAS level, or {@code null} to keep the signature's current level
     */
    public record SignatureChange(Long signatureId, SignatureMethod method, SignatureLevel level) {
    }
}
