package com.foremen.service.signing.provider;

/**
 * FOR-05-08 (Requirements 5.3, 6.4; design §Components {@code SignatureProvider abstraction}): the
 * result of {@link SignatureProvider#verify}, reporting whether a provider's returned sealed
 * evidence is integrity-valid against the document's stored {@code contentHash}.
 *
 * <p>{@link #verified} is {@code true} iff the evidence's recomputed hash matches the stored
 * {@code contentHash} the session was created for; a {@code false} outcome means the signature must
 * <b>not</b> be marked {@code SIGNED} (the caller rejects with {@code error.document.hash.mismatch},
 * Requirement 6.4). {@link #evidenceHash} is the hash the provider actually computed over the
 * evidence, carried for auditing/diagnostics.
 *
 * <p>Immutable carrier, free of any concrete-provider detail (Requirement 5.4).
 *
 * @param verified     whether the evidence integrity matches the stored {@code contentHash}
 * @param evidenceHash the hash recomputed over the supplied evidence; never {@code null}
 */
public record SignatureOutcome(boolean verified, String evidenceHash) {

    public SignatureOutcome {
        if (evidenceHash == null) {
            throw new IllegalArgumentException("evidenceHash must not be null");
        }
    }
}
