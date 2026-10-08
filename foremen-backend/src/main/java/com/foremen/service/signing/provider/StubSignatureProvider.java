package com.foremen.service.signing.provider;

import com.foremen.dao.model.SignatureLevel;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * FOR-05-08 (Requirements 5.1, 5.3, 5.4, 5.5; design §Components {@code SignatureProvider
 * abstraction + StubSignatureProvider} / key decision 6): the <b>default</b> {@link
 * SignatureProvider} that models the {@code ONLINE} / {@code PODPIS_GOV_PL} ceremonies <b>without</b>
 * contacting a live QTSP / podpis.gov.pl.
 *
 * <p>It is selected by configuration — active when {@code foremen.signing.provider=stub} and also
 * when the property is <b>absent</b> ({@code matchIfMissing = true}), so a plain default
 * configuration runs with the stub and the integrity path is exercised end-to-end (Requirement 5.4).
 * A concrete live provider replaces it later by setting {@code foremen.signing.provider} to another
 * value (and contributing its own {@code @ConditionalOnProperty} bean), pluggable via FOR-12 /
 * config without redesign.
 *
 * <p>Behaviour:
 *
 * <ul>
 *   <li>{@link #createSession} returns a <b>synthetic</b> {@code providerRef} (a random {@code stub-}
 *       prefixed UUID) and a {@link SignatureSession.SignatureSessionStatus#PENDING PENDING}
 *       session, driving the initiate → pending → callback → signed/declined flow (Requirement 5.3);
 *       it makes no outbound call.</li>
 *   <li>{@link #verify} <b>recomputes</b> the sha-256 of the supplied (synthetic) evidence and
 *       compares it to the document's stored {@code contentHash}, returning
 *       {@link SignatureOutcome#verified() verified == true} iff they match — so the hash-integrity
 *       branch (and its mismatch rejection) is exercised with no live provider (Requirements 5.3,
 *       6.4).</li>
 * </ul>
 *
 * <p>This provider is never consulted for {@code PRINT} / {@code TABLET_INITIALS}, whose completion
 * is evidence-upload driven; its presence or absence therefore never affects those methods
 * (Requirement 5.5).
 */
@Component
@ConditionalOnProperty(name = "foremen.signing.provider", havingValue = "stub", matchIfMissing = true)
public class StubSignatureProvider implements SignatureProvider {

    /** Prefix marking a {@code providerRef} as issued by this stub (aids diagnostics/auditing). */
    static final String PROVIDER_REF_PREFIX = "stub-";

    @Override
    public SignatureSession createSession(
            URI documentUri, String contentHash, Signer signer, SignatureLevel level) {
        if (documentUri == null) {
            throw new IllegalArgumentException("documentUri must not be null");
        }
        if (contentHash == null) {
            throw new IllegalArgumentException("contentHash must not be null");
        }
        if (signer == null) {
            throw new IllegalArgumentException("signer must not be null");
        }
        if (level == null) {
            throw new IllegalArgumentException("level must not be null");
        }
        // Synthetic, provider-side correlation id — no live QTSP / podpis.gov.pl is contacted.
        String providerRef = PROVIDER_REF_PREFIX + UUID.randomUUID();
        return new SignatureSession(
                providerRef, SignatureSession.SignatureSessionStatus.PENDING);
    }

    @Override
    public SignatureOutcome verify(String providerRef, String contentHash, byte[] evidence) {
        if (providerRef == null) {
            throw new IllegalArgumentException("providerRef must not be null");
        }
        if (contentHash == null) {
            throw new IllegalArgumentException("contentHash must not be null");
        }
        if (evidence == null) {
            throw new IllegalArgumentException("evidence must not be null");
        }
        // Recompute the evidence hash and compare it to the document's stored contentHash, so the
        // integrity path (and its mismatch rejection) runs end-to-end without a live provider.
        String evidenceHash = sha256Hex(evidence);
        boolean verified = constantTimeEquals(evidenceHash, contentHash);
        return new SignatureOutcome(verified, evidenceHash);
    }

    /**
     * Computes the lowercase hex sha-256 of the given bytes.
     *
     * @param bytes the bytes to hash; never {@code null}
     * @return the 64-char lowercase hex digest
     */
    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm; this cannot happen on a conformant runtime.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Length-constant comparison of two hash strings (avoids leaking match position via timing).
     *
     * @param a the first value, or {@code null}
     * @param b the second value, or {@code null}
     * @return {@code true} iff both are non-null and equal
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
