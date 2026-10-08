package com.foremen.service.signing;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.DocumentSignatureEntity;
import com.foremen.dao.model.SignatureStatus;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link SigningProgressCalculator#compute(List)} — the pure signing-progress
 * calculation (FOR-05-08, Requirements 4.5, 9.4; design §Components {@code SigningProgressCalculator}).
 *
 * <p>The calculator is exercised directly against in-memory {@link DocumentSignatureEntity} lists —
 * no persistence, no Spring context — so Property 5 is cheap to run over arbitrary signature sets
 * generated across every {@link SignatureStatus} value.
 *
 * <p>Feature: FOR-05-08-document-signing, Property 5: signing progress is a pure function of the
 * signature set.
 *
 * <p><b>Validates: Requirements 4.5, 9.4</b>
 */
@Tag("Feature: FOR-05-08-document-signing, Property 5: signing progress is a pure function of the signature set")
class SigningProgressCalculatorPropertyTest {

    // ------------------------------------------------------------------------------------------
    // Property 5a: totalCount is exactly the list size.
    // Validates: Requirements 4.5, 9.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-08-document-signing, Property 5: signing progress is a pure function of the signature set")
    void totalCountEqualsListSize(@ForAll("signatureSets") List<DocumentSignatureEntity> signatures) {
        SigningProgress progress = SigningProgressCalculator.compute(signatures);

        assertThat(progress.totalCount()).isEqualTo(signatures.size());
    }

    // ------------------------------------------------------------------------------------------
    // Property 5b: signedCount is exactly the count of SIGNED signatures.
    // Validates: Requirements 4.5, 9.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-08-document-signing, Property 5: signing progress is a pure function of the signature set")
    void signedCountEqualsNumberOfSignedSignatures(
            @ForAll("signatureSets") List<DocumentSignatureEntity> signatures) {
        long expectedSigned = signatures.stream()
                .filter(signature -> signature.getStatus() == SignatureStatus.SIGNED)
                .count();

        SigningProgress progress = SigningProgressCalculator.compute(signatures);

        assertThat(progress.signedCount()).isEqualTo((int) expectedSigned);
    }

    // ------------------------------------------------------------------------------------------
    // Property 5c: outstanding size is totalCount - signedCount (and all counts are in range).
    // Validates: Requirements 4.5, 9.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-08-document-signing, Property 5: signing progress is a pure function of the signature set")
    void outstandingSizeIsTotalMinusSigned(
            @ForAll("signatureSets") List<DocumentSignatureEntity> signatures) {
        SigningProgress progress = SigningProgressCalculator.compute(signatures);

        assertThat(progress.signedCount()).isBetween(0, progress.totalCount());
        assertThat(progress.outstanding()).hasSize(progress.totalCount() - progress.signedCount());
    }

    // ------------------------------------------------------------------------------------------
    // Property 5d: allSigned == (signedCount == totalCount && totalCount > 0) — the core invariant;
    // an empty signature set is never all-signed.
    // Validates: Requirements 4.5, 9.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-08-document-signing, Property 5: signing progress is a pure function of the signature set")
    void allSignedMatchesInvariant(@ForAll("signatureSets") List<DocumentSignatureEntity> signatures) {
        SigningProgress progress = SigningProgressCalculator.compute(signatures);

        boolean expectedAllSigned =
                progress.signedCount() == progress.totalCount() && progress.totalCount() > 0;

        assertThat(progress.allSigned()).isEqualTo(expectedAllSigned);
    }

    // ------------------------------------------------------------------------------------------
    // Property 5e: purity — the same input always yields an equal result, and the calculator
    // never mutates the input list.
    // Validates: Requirements 4.5, 9.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-08-document-signing, Property 5: signing progress is a pure function of the signature set")
    void computeIsPureAndRepeatable(@ForAll("signatureSets") List<DocumentSignatureEntity> signatures) {
        int sizeBefore = signatures.size();

        SigningProgress first = SigningProgressCalculator.compute(signatures);
        SigningProgress second = SigningProgressCalculator.compute(signatures);

        assertThat(second).isEqualTo(first);
        assertThat(signatures).hasSize(sizeBefore);
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /**
     * Arbitrary signature sets over the full {@link SignatureStatus} space, sized 0..12 so empty
     * sets (never "all signed"), single-signer, and multi-signer documents are all exercised.
     */
    @Provide
    Arbitrary<List<DocumentSignatureEntity>> signatureSets() {
        return signatureStatuses()
                .map(this::signatureWithStatus)
                .list()
                .ofMinSize(0)
                .ofMaxSize(12);
    }

    @Provide
    Arbitrary<SignatureStatus> signatureStatuses() {
        return Arbitraries.of(SignatureStatus.class);
    }

    private DocumentSignatureEntity signatureWithStatus(SignatureStatus status) {
        DocumentSignatureEntity signature = new DocumentSignatureEntity();
        signature.setStatus(status);
        signature.setSignerRole("CLIENT");
        return signature;
    }
}
