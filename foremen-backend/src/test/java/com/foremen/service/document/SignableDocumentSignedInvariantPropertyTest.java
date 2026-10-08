package com.foremen.service.document;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.DocumentSignatureEntity;
import com.foremen.dao.model.DocumentStatus;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.dao.model.SignatureStatus;
import com.foremen.service.signing.SigningProgressCalculator;

import org.mockito.Mockito;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for the derived {@code SIGNED} invariant — the parent Property 16 integrity
 * invariant — implemented by {@link SignableDocumentService#recomputeSignedState(SignableDocumentEntity)}
 * (FOR-05-08, Requirements 1.4, 1.5, 1.6, 6.6; design §Components {@code recomputeSignedState}).
 *
 * <p>{@code recomputeSignedState} is the <b>single</b> place {@link DocumentStatus#SIGNED} is ever
 * assigned: a document is {@code SIGNED} <b>iff</b> every one of its {@link DocumentSignatureEntity}
 * is {@link SignatureStatus#SIGNED} and at least one signature exists (so an empty signature set is
 * never all-signed). It only ever <b>promotes</b> a {@link DocumentStatus#PENDING_SIGNATURES}
 * document — it never demotes, and never touches a {@link DocumentStatus#DRAFT},
 * {@link DocumentStatus#VOID}, or already-{@link DocumentStatus#SIGNED} document.
 *
 * <p>The invariant is exercised against the <b>real</b> {@code recomputeSignedState}: the service is
 * instantiated with the single collaborator the method actually reads — a real
 * {@link SigningProgressCalculator} — and {@code null} for every other collaborator, since
 * {@code recomputeSignedState} touches only {@code doc.getStatus()}, {@code doc.getSignatures()}, and
 * the progress calculator. No persistence, no Spring context: the method is pure over the in-memory
 * document graph.
 *
 * <p>Feature: FOR-05-08-document-signing, Property 1 (parent Property 16): SignableDocument is SIGNED
 * iff all signatures are SIGNED.
 *
 * <p><b>Validates: Requirements 1.4, 1.5, 1.6, 6.6</b>
 */
@Tag("Feature: FOR-05-08-document-signing, Property 1 (parent Property 16): SignableDocument is SIGNED iff all signatures are SIGNED")
class SignableDocumentSignedInvariantPropertyTest {

    /**
     * The service under test, built with the two collaborators {@code recomputeSignedState} actually
     * reaches — a <b>real</b> {@link SigningProgressCalculator} (the invariant under test) and a
     * no-op mock {@link ProjectActivationSignal} (the contract-signed hand-off fired on full-sign,
     * R10.1; its own contract/offer gating is covered by Property 7, so here it is a stubbed no-op) —
     * and {@code null} for every other collaborator, which {@code recomputeSignedState} never
     * dereferences. The constructor only assigns fields, so {@code null} is safe for the rest.
     */
    private final SignableDocumentService service = new SignableDocumentService(
            null, null, null, null, null, null, null, null, null, null, null, null,
            null, new SigningProgressCalculator(), null, null, null,
            Mockito.mock(ProjectActivationSignal.class),
            Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));

    // ------------------------------------------------------------------------------------------
    // Property 1a (parent 16): for a PENDING_SIGNATURES document, recomputeSignedState sets status
    // SIGNED iff every signature is SIGNED and >= 1; otherwise it stays PENDING_SIGNATURES.
    // Validates: Requirements 1.4, 6.6
    // ------------------------------------------------------------------------------------------

    @Property(tries = 300)
    @Tag("Feature: FOR-05-08-document-signing, Property 1 (parent Property 16): SignableDocument is SIGNED iff all signatures are SIGNED")
    void pendingDocumentBecomesSignedIffAllSignaturesSigned(
            @ForAll("signatureStatusSets") List<SignatureStatus> statuses) {
        SignableDocumentEntity doc = documentWith(DocumentStatus.PENDING_SIGNATURES, statuses);

        boolean promoted = service.recomputeSignedState(doc);

        boolean expectedSigned = !statuses.isEmpty()
                && statuses.stream().allMatch(status -> status == SignatureStatus.SIGNED);

        assertThat(promoted).isEqualTo(expectedSigned);
        assertThat(doc.getStatus()).isEqualTo(
                expectedSigned ? DocumentStatus.SIGNED : DocumentStatus.PENDING_SIGNATURES);
    }

    // ------------------------------------------------------------------------------------------
    // Property 1b (parent 16): an empty signature set never yields SIGNED, even on a
    // PENDING_SIGNATURES document (a document with no designated signers is not "fully signed").
    // Validates: Requirements 1.4, 6.6
    // ------------------------------------------------------------------------------------------

    @Property(tries = 1)
    @Tag("Feature: FOR-05-08-document-signing, Property 1 (parent Property 16): SignableDocument is SIGNED iff all signatures are SIGNED")
    void emptySignatureSetNeverYieldsSigned() {
        SignableDocumentEntity doc = documentWith(DocumentStatus.PENDING_SIGNATURES, List.of());

        boolean promoted = service.recomputeSignedState(doc);

        assertThat(promoted).isFalse();
        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PENDING_SIGNATURES);
    }

    // ------------------------------------------------------------------------------------------
    // Property 1c (parent 16): recomputeSignedState never changes a document that is not
    // PENDING_SIGNATURES. A DRAFT, VOID, or already-SIGNED document — with any signature set — is
    // left exactly as it was (no promotion, no demotion). This encodes the immutable-after-freeze
    // and terminal-state invariants (R1.5, R1.6).
    // Validates: Requirements 1.4, 1.5, 1.6, 6.6
    // ------------------------------------------------------------------------------------------

    @Property(tries = 300)
    @Tag("Feature: FOR-05-08-document-signing, Property 1 (parent Property 16): SignableDocument is SIGNED iff all signatures are SIGNED")
    void nonPendingDocumentIsNeverChanged(
            @ForAll("nonPendingStatuses") DocumentStatus startStatus,
            @ForAll("signatureStatusSets") List<SignatureStatus> statuses) {
        SignableDocumentEntity doc = documentWith(startStatus, statuses);

        boolean promoted = service.recomputeSignedState(doc);

        assertThat(promoted).isFalse();
        assertThat(doc.getStatus()).isEqualTo(startStatus);
    }

    // ------------------------------------------------------------------------------------------
    // Property 1d (parent 16): recomputeSignedState only ever promotes — it never demotes. A
    // PENDING_SIGNATURES document whose signatures are not all SIGNED stays PENDING_SIGNATURES, and
    // re-invoking it on an already-SIGNED document is an idempotent no-op.
    // Validates: Requirements 1.4, 6.6
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-08-document-signing, Property 1 (parent Property 16): SignableDocument is SIGNED iff all signatures are SIGNED")
    void recomputeIsIdempotentAndNeverDemotes(
            @ForAll("signatureStatusSets") List<SignatureStatus> statuses) {
        SignableDocumentEntity doc = documentWith(DocumentStatus.PENDING_SIGNATURES, statuses);

        service.recomputeSignedState(doc);
        DocumentStatus afterFirst = doc.getStatus();

        boolean promotedAgain = service.recomputeSignedState(doc);

        // A second call never changes the status again (idempotent) and never demotes.
        assertThat(doc.getStatus()).isEqualTo(afterFirst);
        if (afterFirst == DocumentStatus.SIGNED) {
            assertThat(promotedAgain).isFalse();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 1e: a null document is a no-op (never throws, never promotes).
    // Validates: Requirements 1.4, 6.6
    // ------------------------------------------------------------------------------------------

    @Property(tries = 1)
    @Tag("Feature: FOR-05-08-document-signing, Property 1 (parent Property 16): SignableDocument is SIGNED iff all signatures are SIGNED")
    void nullDocumentIsNoOp() {
        boolean promoted = service.recomputeSignedState(null);

        assertThat(promoted).isFalse();
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures and generators
    // ------------------------------------------------------------------------------------------

    /**
     * Builds an in-memory document in the given status with one {@link DocumentSignatureEntity} per
     * supplied status. No persistence — the document graph is purely in-memory.
     */
    private SignableDocumentEntity documentWith(DocumentStatus status, List<SignatureStatus> statuses) {
        SignableDocumentEntity doc = new SignableDocumentEntity();
        doc.setStatus(status);
        List<DocumentSignatureEntity> signatures = new ArrayList<>();
        for (SignatureStatus signatureStatus : statuses) {
            DocumentSignatureEntity signature = new DocumentSignatureEntity();
            signature.setStatus(signatureStatus);
            signature.setSignerRole("CLIENT");
            signatures.add(signature);
        }
        doc.setSignatures(signatures);
        return doc;
    }

    /**
     * Arbitrary signature-status sets over the full {@link SignatureStatus} space, sized 0..12 so
     * empty sets (never all-signed), single-signer, and multi-signer documents — all-SIGNED,
     * partially-SIGNED, and DECLINED-mixed — are all exercised.
     */
    @Provide
    Arbitrary<List<SignatureStatus>> signatureStatusSets() {
        return Arbitraries.of(SignatureStatus.class).list().ofMinSize(0).ofMaxSize(12);
    }

    /** The three non-{@code PENDING_SIGNATURES} statuses the recompute must never touch. */
    @Provide
    Arbitrary<DocumentStatus> nonPendingStatuses() {
        return Arbitraries.of(DocumentStatus.DRAFT, DocumentStatus.SIGNED, DocumentStatus.VOID);
    }
}
