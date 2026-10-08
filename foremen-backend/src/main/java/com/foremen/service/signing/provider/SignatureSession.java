package com.foremen.service.signing.provider;

/**
 * FOR-05-08 (Requirements 5.3, 5.4; design §Components {@code SignatureProvider abstraction}): the
 * handle returned by {@link SignatureProvider#createSession} when a signing ceremony is initiated
 * for an {@code ONLINE} / {@code PODPIS_GOV_PL} signature.
 *
 * <p>The {@link #providerRef} is the opaque, provider-side correlation id persisted on the
 * {@code DocumentSignatureEntity.providerRef} and later echoed back on the provider callback
 * ({@code SignatureCallbackController}) so the inbound evidence can be matched to its signature.
 * {@link #status} reports where the newly created session sits in the
 * initiate → pending → callback → signed/declined flow — a freshly created session is
 * {@link SignatureSessionStatus#PENDING} (design: the stub drives the full status flow).
 *
 * <p>Immutable carrier; it names nothing about any concrete provider so a live QTSP / Profil
 * Zaufany implementation can return the same shape (Requirement 5.4).
 *
 * @param providerRef the opaque provider-side session reference; never {@code null}
 * @param status      the session status immediately after creation; never {@code null}
 */
public record SignatureSession(String providerRef, SignatureSessionStatus status) {

    public SignatureSession {
        if (providerRef == null || providerRef.isBlank()) {
            throw new IllegalArgumentException("providerRef must not be null or blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
    }

    /**
     * The lifecycle status of a provider signing session, mirroring the
     * initiate → pending → callback → signed/declined flow the abstraction models (Requirement 5.4).
     */
    public enum SignatureSessionStatus {
        /** The ceremony has been initiated and is awaiting the signer's action / the callback. */
        PENDING,
        /** The signer completed the ceremony; the sealed evidence is ready for verification. */
        SIGNED,
        /** The signer declined the ceremony. */
        DECLINED
    }
}
