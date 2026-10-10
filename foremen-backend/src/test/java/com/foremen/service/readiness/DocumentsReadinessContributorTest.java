package com.foremen.service.readiness;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.foremen.dao.model.DocumentStatus;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.dao.model.SignableDocumentTypeEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DocumentsReadinessContributor}'s status → state/percentage mapping and
 * Contract_Document selection (FOR-05-11 Requirement 5.4, 5.7).
 *
 * <p>The derivation is exercised directly through its pure, Spring-free entry points
 * ({@link DocumentsReadinessContributor#deriveContribution} and
 * {@link DocumentsReadinessContributor#contractDocument}), so no persistence or Spring context is
 * needed.
 *
 * <p>Feature: FOR-05-11-project-readiness
 *
 * <p><b>Validates: Requirements 5.4, 5.7</b>
 */
class DocumentsReadinessContributorTest {

    @Test
    void signedContractIsDoneAtHundredWithNoItems() {
        AreaContribution contribution =
                DocumentsReadinessContributor.deriveContribution(DocumentStatus.SIGNED);

        assertCommonShape(contribution);
        assertThat(contribution.state()).isEqualTo(ReadinessState.DONE);
        assertThat(contribution.percentage()).isEqualTo(100);
        assertThat(contribution.items()).isEmpty();
    }

    @Test
    void pendingSignaturesContractIsPartialAtFiftyWithPendingItem() {
        AreaContribution contribution =
                DocumentsReadinessContributor.deriveContribution(DocumentStatus.PENDING_SIGNATURES);

        assertCommonShape(contribution);
        assertThat(contribution.state()).isEqualTo(ReadinessState.PARTIAL);
        assertThat(contribution.percentage()).isEqualTo(50);
        assertThat(contribution.items()).singleElement().satisfies(item -> {
            assertThat(item.descriptionKey()).isEqualTo("readiness.documents.pending");
            assertThat(item.params()).isEmpty();
            assertThat(item.targetTab()).isEqualTo("documentSigning");
        });
    }

    @Test
    void draftContractIsBlockedAtZeroWithNotReadyItem() {
        assertBlockedNotReady(
                DocumentsReadinessContributor.deriveContribution(DocumentStatus.DRAFT));
    }

    @Test
    void voidContractIsBlockedAtZeroWithNotReadyItem() {
        assertBlockedNotReady(
                DocumentsReadinessContributor.deriveContribution(DocumentStatus.VOID));
    }

    @Test
    void noContractDocumentIsBlockedAtZeroWithNotReadyItem() {
        assertBlockedNotReady(DocumentsReadinessContributor.deriveContribution(null));
    }

    @Test
    void itemsAreEmptyIffDone() {
        DocumentStatus[] statuses = {
                DocumentStatus.DRAFT,
                DocumentStatus.PENDING_SIGNATURES,
                DocumentStatus.SIGNED,
                DocumentStatus.VOID,
                null
        };
        for (DocumentStatus status : statuses) {
            AreaContribution contribution = DocumentsReadinessContributor.deriveContribution(status);
            boolean fullyReady =
                    contribution.state() == ReadinessState.DONE && contribution.percentage() == 100;
            assertThat(contribution.items().isEmpty())
                    .as("items empty iff done for status %s", status)
                    .isEqualTo(fullyReady);
        }
    }

    // --- contract document selection ---

    @Test
    void contractDocumentIsEmptyWhenNoDocuments() {
        assertThat(DocumentsReadinessContributor.contractDocument(List.of())).isEmpty();
    }

    @Test
    void contractDocumentIsEmptyWhenNoContractTypedDocument() {
        SignableDocumentEntity annex = document(1L, "ANNEX", DocumentStatus.SIGNED);
        SignableDocumentEntity protocol = document(2L, "PROTOCOL", DocumentStatus.PENDING_SIGNATURES);

        assertThat(DocumentsReadinessContributor.contractDocument(List.of(protocol, annex))).isEmpty();
    }

    @Test
    void contractDocumentPicksTheNewestContractTypedDocument() {
        // The DAO returns newest first, so the first CONTRACT_* in the list is the newest.
        SignableDocumentEntity newestContract = document(3L, "CONTRACT_MAIN", DocumentStatus.PENDING_SIGNATURES);
        SignableDocumentEntity olderContract = document(1L, "CONTRACT_MAIN", DocumentStatus.SIGNED);
        SignableDocumentEntity annex = document(2L, "ANNEX", DocumentStatus.SIGNED);

        Optional<SignableDocumentEntity> contract = DocumentsReadinessContributor.contractDocument(
                List.of(newestContract, annex, olderContract));

        assertThat(contract).containsSame(newestContract);
    }

    @Test
    void contractDocumentIgnoresDocumentsWithoutATypeOrCode() {
        SignableDocumentEntity noType = document(2L, null, DocumentStatus.SIGNED);
        SignableDocumentEntity contract = document(1L, "CONTRACT_MAIN", DocumentStatus.SIGNED);

        Optional<SignableDocumentEntity> picked = DocumentsReadinessContributor.contractDocument(
                List.of(noType, contract));

        assertThat(picked).containsSame(contract);
    }

    @Test
    void documentsAreaKeyOrderResourceAndTargetTabAreStable() {
        DocumentsReadinessContributor contributor = new DocumentsReadinessContributor(null);

        assertThat(contributor.areaKey()).isEqualTo("documents");
        assertThat(contributor.areaResource()).isEqualTo("SIGNABLE_DOCUMENTS");
        assertThat(contributor.order()).isEqualTo(40);
    }

    // --- helpers ---

    private static void assertCommonShape(AreaContribution contribution) {
        assertThat(contribution.key()).isEqualTo("documents");
        assertThat(contribution.nameKey()).isEqualTo("workspace.tab.documentSigning");
        assertThat(contribution.percentage()).isBetween(0, 100);
    }

    private static void assertBlockedNotReady(AreaContribution contribution) {
        assertCommonShape(contribution);
        assertThat(contribution.state()).isEqualTo(ReadinessState.BLOCKED);
        assertThat(contribution.percentage()).isZero();
        assertThat(contribution.items()).singleElement().satisfies(item -> {
            assertThat(item.descriptionKey()).isEqualTo("readiness.documents.notReady");
            assertThat(item.params()).isEmpty();
            assertThat(item.targetTab()).isEqualTo("documentSigning");
        });
    }

    private static SignableDocumentEntity document(long id, String typeCode, DocumentStatus status) {
        SignableDocumentEntity document = new SignableDocumentEntity();
        document.setId(id);
        document.setStatus(status);
        if (typeCode != null) {
            SignableDocumentTypeEntity type = new SignableDocumentTypeEntity();
            type.setCode(typeCode);
            document.setDocumentType(type);
        }
        return document;
    }
}
