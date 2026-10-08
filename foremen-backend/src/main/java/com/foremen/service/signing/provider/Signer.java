package com.foremen.service.signing.provider;

/**
 * FOR-05-08 (Requirement 5.3; design §Components {@code SignatureProvider abstraction}): the
 * minimal signer identity handed to a {@link SignatureProvider} when a signing ceremony is
 * initiated for an {@code ONLINE} / {@code PODPIS_GOV_PL} signature.
 *
 * <p>A plain, immutable carrier — it holds only what a provider ceremony needs to address the
 * signer, decoupled from the persistence model ({@code DocumentSignatureEntity}) so the provider
 * abstraction has no dependency on JPA. Any field may be {@code null} when unknown (e.g. a
 * role-resolved signer with no explicit user), mirroring the nullable {@code signerUser} /
 * {@code signerRole} of a {@code DocumentSignature}.
 *
 * @param userId    the signer's user id, or {@code null} when the signer is resolved purely by role
 * @param email     the signer's email used by the ceremony to reach them, or {@code null}
 * @param fullName  the signer's display name, or {@code null}
 * @param role      the signer role (e.g. {@code CLIENT}), or {@code null}
 */
public record Signer(Long userId, String email, String fullName, String role) {
}
