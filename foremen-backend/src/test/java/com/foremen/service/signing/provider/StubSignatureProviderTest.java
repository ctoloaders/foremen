package com.foremen.service.signing.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foremen.dao.model.SignatureLevel;
import com.foremen.service.signing.provider.SignatureSession.SignatureSessionStatus;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration (focused) test of the {@link StubSignatureProvider} ceremony contract (FOR-05-08 task
 * 6.5; Requirements 5.3, 6.4).
 *
 * <p>Exercises the provider half of the {@code ONLINE} / {@code PODPIS_GOV_PL}
 * initiate → pending → callback → signed flow <b>without</b> the {@code SignatureService}
 * {@code onProviderCallback} wiring (that is task 7.4). Concretely it verifies:
 *
 * <ul>
 *   <li><b>initiate</b> ({@link StubSignatureProvider#createSession}) returns a {@code PENDING}
 *       session carrying a synthetic {@code stub-}-prefixed {@code providerRef} (Requirement 5.3).
 *   </li>
 *   <li><b>verify-success</b> ({@link StubSignatureProvider#verify}) returns
 *       {@code verified == true} when the sealed evidence hashes to the document's stored
 *       {@code contentHash} — i.e. the callback evidence that would drive the signature to
 *       {@code SIGNED} (Requirements 5.3, 6.4).</li>
 *   <li><b>verify-mismatch</b> returns {@code verified == false} when the evidence does not hash to
 *       the stored {@code contentHash} — the hash-mismatch rejection branch (Requirement 6.4).</li>
 * </ul>
 *
 * The stub is a pure, dependency-free component selected by default configuration, so it is
 * instantiated directly with no Spring context (mirroring the sibling signing tests).
 */
@DisplayName("StubSignatureProvider — provider ceremony contract (initiate → verify)")
class StubSignatureProviderTest {

    private final StubSignatureProvider provider = new StubSignatureProvider();

    private static final URI DOCUMENT_URI = URI.create("foremen://media/frozen-contract.pdf");
    private static final Signer SIGNER =
            new Signer(42L, "client@example.com", "Jan Kowalski", "CLIENT");

    /** Lowercase hex sha-256, matching the provider's own digest, so expectations are independent. */
    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // --- initiate: createSession returns PENDING + a stub- prefixed providerRef (R5.3) ---

    @Nested
    @DisplayName("initiate (createSession)")
    class Initiate {

        @Test
        @DisplayName("returns a PENDING session with a non-blank stub- prefixed providerRef")
        void returnsPendingStubSession() {
            byte[] frozenPdf = "frozen signing artifact".getBytes(StandardCharsets.UTF_8);
            String contentHash = sha256Hex(frozenPdf);

            SignatureSession session =
                    provider.createSession(DOCUMENT_URI, contentHash, SIGNER, SignatureLevel.AdES);

            assertThat(session).isNotNull();
            assertThat(session.status()).isEqualTo(SignatureSessionStatus.PENDING);
            assertThat(session.providerRef()).startsWith("stub-").hasSizeGreaterThan("stub-".length());
        }

        @Test
        @DisplayName("issues a distinct providerRef on each initiate")
        void issuesDistinctProviderRefs() {
            byte[] frozenPdf = "frozen signing artifact".getBytes(StandardCharsets.UTF_8);
            String contentHash = sha256Hex(frozenPdf);

            SignatureSession first =
                    provider.createSession(DOCUMENT_URI, contentHash, SIGNER, SignatureLevel.AdES);
            SignatureSession second =
                    provider.createSession(DOCUMENT_URI, contentHash, SIGNER, SignatureLevel.AdES);

            assertThat(first.providerRef()).isNotEqualTo(second.providerRef());
        }

        @Test
        @DisplayName("rejects a null argument")
        void rejectsNullArguments() {
            String contentHash = sha256Hex("x".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() ->
                            provider.createSession(null, contentHash, SIGNER, SignatureLevel.AdES))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() ->
                            provider.createSession(DOCUMENT_URI, null, SIGNER, SignatureLevel.AdES))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() ->
                            provider.createSession(DOCUMENT_URI, contentHash, null, SignatureLevel.AdES))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() ->
                            provider.createSession(DOCUMENT_URI, contentHash, SIGNER, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // --- verify: the integrity branch that gates SIGNED (R5.3, R6.4) ---

    @Nested
    @DisplayName("verify (sealed-evidence integrity)")
    class Verify {

        @Test
        @DisplayName("returns verified=true when the evidence hashes to the stored contentHash")
        void verifiesMatchingEvidence() {
            byte[] evidence = "sealed online-signature evidence".getBytes(StandardCharsets.UTF_8);
            String contentHash = sha256Hex(evidence);

            SignatureSession session =
                    provider.createSession(DOCUMENT_URI, contentHash, SIGNER, SignatureLevel.AdES);

            SignatureOutcome outcome =
                    provider.verify(session.providerRef(), contentHash, evidence);

            assertThat(outcome.verified()).isTrue();
            assertThat(outcome.evidenceHash()).isEqualTo(contentHash);
        }

        @Test
        @DisplayName("returns verified=false when the evidence does not hash to the stored contentHash")
        void rejectsMismatchedEvidence() {
            byte[] evidence = "sealed online-signature evidence".getBytes(StandardCharsets.UTF_8);
            // contentHash is bound to a DIFFERENT (frozen) artifact than the returned evidence.
            String contentHash =
                    sha256Hex("a different frozen artifact".getBytes(StandardCharsets.UTF_8));

            SignatureSession session =
                    provider.createSession(DOCUMENT_URI, contentHash, SIGNER, SignatureLevel.AdES);

            SignatureOutcome outcome =
                    provider.verify(session.providerRef(), contentHash, evidence);

            assertThat(outcome.verified()).isFalse();
            // the recomputed evidence hash is reported, and it is NOT the stored contentHash
            assertThat(outcome.evidenceHash())
                    .isEqualTo(sha256Hex(evidence))
                    .isNotEqualTo(contentHash);
        }

        @Test
        @DisplayName("rejects a null argument")
        void rejectsNullArguments() {
            byte[] evidence = "e".getBytes(StandardCharsets.UTF_8);
            String contentHash = sha256Hex(evidence);
            assertThatThrownBy(() -> provider.verify(null, contentHash, evidence))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> provider.verify("stub-ref", null, evidence))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> provider.verify("stub-ref", contentHash, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // --- end-to-end provider half: initiate → verify success drives toward SIGNED ---

    @Test
    @DisplayName("initiate then verify-success completes the provider half of the SIGNED flow")
    void initiateThenVerifySuccess() {
        byte[] evidence = "signed contract bytes".getBytes(StandardCharsets.UTF_8);
        String contentHash = sha256Hex(evidence);

        SignatureSession session =
                provider.createSession(DOCUMENT_URI, contentHash, SIGNER, SignatureLevel.QES);
        assertThat(session.status()).isEqualTo(SignatureSessionStatus.PENDING);

        // The callback would carry back the providerRef + sealed evidence; verify gates SIGNED.
        SignatureOutcome outcome =
                provider.verify(session.providerRef(), contentHash, evidence);
        assertThat(outcome.verified()).isTrue();
    }
}
