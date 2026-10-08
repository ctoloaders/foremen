package com.foremen.service.signing.provider;

import com.foremen.dao.model.SignatureLevel;
import java.net.URI;

/**
 * FOR-05-08 (Requirement 5.3, 5.4, 5.5; parent design §7.2; design §Components {@code
 * SignatureProvider abstraction + StubSignatureProvider}): the abstraction behind the {@code ONLINE}
 * and {@code PODPIS_GOV_PL} signing ceremonies.
 *
 * <p>The abstraction supplies the two entry points the signing lifecycle needs: {@link
 * #createSession} to <b>initiate</b> a signing ceremony (returning an opaque {@code providerRef} and
 * a {@link SignatureSession} in the {@code PENDING} status), and {@link #verify} to check the
 * integrity of the provider's returned <b>sealed evidence</b> against the document's stored
 * {@code contentHash} before a signature is marked {@code SIGNED} (Requirement 6.4). Together these
 * drive the initiate → pending → callback → signed/declined flow.
 *
 * <p>The concrete provider is chosen by configuration ({@code foremen.signing.provider}); the
 * default {@link StubSignatureProvider} drives the full status flow without contacting a live QTSP /
 * podpis.gov.pl, and a real provider is pluggable later via FOR-12 / config without redesign
 * (Requirements 5.4). Crucially, the {@code PRINT} and {@code TABLET_INITIALS} methods do <b>not</b>
 * use this abstraction, so the absence of a configured live provider never breaks them
 * (Requirement 5.5).
 */
public interface SignatureProvider {

    /**
     * Initiates a signing ceremony for one signer and returns the session handle.
     *
     * @param documentUri the frozen signing artifact (the immutable PDF) to be signed; never
     *                    {@code null}
     * @param contentHash the sha-256 of the frozen artifact the session is bound to; the evidence
     *                    later returned must verify against this hash (Requirement 6.4); never
     *                    {@code null}
     * @param signer      the signer the ceremony addresses; never {@code null}
     * @param level       the requested eIDAS assurance level (default {@code AdES}); never
     *                    {@code null}
     * @return a {@link SignatureSession} with an opaque {@code providerRef} and the {@code PENDING}
     *         status
     */
    SignatureSession createSession(
            URI documentUri, String contentHash, Signer signer, SignatureLevel level);

    /**
     * Verifies the integrity of the sealed evidence returned by the provider for a session against
     * the document's stored {@code contentHash}.
     *
     * <p>The signature may be marked {@code SIGNED} only when the returned outcome is
     * {@link SignatureOutcome#verified() verified}; otherwise the caller rejects the completion with
     * {@code error.document.hash.mismatch} (Requirement 6.4).
     *
     * @param providerRef the opaque session reference originally issued by {@link #createSession};
     *                    never {@code null}
     * @param contentHash the document's stored content hash the evidence must match; never
     *                    {@code null}
     * @param evidence    the sealed evidence bytes returned by the provider; never {@code null}
     * @return the verification {@link SignatureOutcome}
     */
    SignatureOutcome verify(String providerRef, String contentHash, byte[] evidence);
}
