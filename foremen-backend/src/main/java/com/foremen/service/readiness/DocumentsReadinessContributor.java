package com.foremen.service.readiness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.foremen.dao.SignableDocumentDao;
import com.foremen.dao.model.DocumentStatus;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.dao.model.SignableDocumentTypeEntity;

/**
 * The {@code documents} area's readiness contribution (FOR-05-11 Requirement 5.4).
 *
 * <p>Reports where the project's contract stands on the way to a fully-signed document. The area's
 * state is driven by the project's <b>Contract_Document</b> — the newest {@link SignableDocumentEntity}
 * whose {@link SignableDocumentTypeEntity#getCode() type code} starts with {@code CONTRACT_}
 * (FOR-05-08's contract-type prefix convention) — projected onto its {@link DocumentStatus}:
 *
 * <ul>
 *   <li>{@link DocumentStatus#SIGNED} → {@link ReadinessState#DONE}, {@code 100%}, no items
 *       (the contract is fully signed).</li>
 *   <li>{@link DocumentStatus#PENDING_SIGNATURES} → {@link ReadinessState#PARTIAL}, {@code 50%}, one
 *       {@code readiness.documents.pending} item (the contract is awaiting signatures).</li>
 *   <li>otherwise — no Contract_Document at all, or it is {@link DocumentStatus#DRAFT} /
 *       {@link DocumentStatus#VOID} → {@link ReadinessState#BLOCKED}, {@code 0%}, one
 *       {@code readiness.documents.notReady} item (prepare / sign the contract).</li>
 * </ul>
 *
 * <p>The Contract_Document is resolved from {@link SignableDocumentDao#findByProjectIdOrderByIdDesc(Long)}
 * (newest first): the first document whose type code carries the {@code CONTRACT_} prefix. All items
 * target the {@code documentSigning} workspace tab. This contributor is side-effect free
 * (Requirement 1.6).
 */
@Component
public class DocumentsReadinessContributor implements ProjectReadinessContributor {

    static final String AREA_KEY = "documents";
    static final String NAME_KEY = "workspace.tab.documentSigning";
    static final String TARGET_TAB = "documentSigning";
    static final String ITEM_PENDING = "readiness.documents.pending";
    static final String ITEM_NOT_READY = "readiness.documents.notReady";

    /** The contract-type prefix (FOR-05-08 R10.1): a Contract_Document's type code starts with this. */
    static final String CONTRACT_TYPE_PREFIX = "CONTRACT_";

    private final SignableDocumentDao signableDocumentDao;

    public DocumentsReadinessContributor(SignableDocumentDao signableDocumentDao) {
        this.signableDocumentDao = signableDocumentDao;
    }

    @Override
    public String areaKey() {
        return AREA_KEY;
    }

    @Override
    public String areaResource() {
        return "SIGNABLE_DOCUMENTS";
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public AreaContribution contribute(Long projectId) {
        List<SignableDocumentEntity> documentsNewestFirst =
                signableDocumentDao.findByProjectIdOrderByIdDesc(projectId);
        DocumentStatus contractStatus = contractDocument(documentsNewestFirst)
                .map(SignableDocumentEntity::getStatus)
                .orElse(null);
        return deriveContribution(contractStatus);
    }

    /**
     * The project's Contract_Document among its documents ordered newest-first: the first document
     * whose {@linkplain SignableDocumentTypeEntity#getCode() type code} carries the
     * {@value #CONTRACT_TYPE_PREFIX} prefix (so "newest Contract_Document" = the first {@code CONTRACT_*}
     * in the id-desc list).
     *
     * @param documentsNewestFirst the project's documents, newest first (as the DAO returns them)
     * @return the newest Contract_Document, or empty when the project has none
     */
    static Optional<SignableDocumentEntity> contractDocument(
            List<SignableDocumentEntity> documentsNewestFirst) {
        return documentsNewestFirst.stream()
                .filter(DocumentsReadinessContributor::isContractDocument)
                .findFirst();
    }

    private static boolean isContractDocument(SignableDocumentEntity document) {
        SignableDocumentTypeEntity type = document.getDocumentType();
        return type != null
                && type.getCode() != null
                && type.getCode().startsWith(CONTRACT_TYPE_PREFIX);
    }

    /**
     * The pure derivation of the {@code documents} contribution from the project's Contract_Document
     * status (or {@code null} when the project has no Contract_Document). Spring-free so the
     * status → state/percentage mapping is directly unit-testable (FOR-05-11 Requirement 5.4).
     *
     * @param contractStatus the Contract_Document's {@link DocumentStatus}, or {@code null} when there
     *                       is no Contract_Document
     * @return the well-formed {@code documents} area contribution
     */
    static AreaContribution deriveContribution(DocumentStatus contractStatus) {
        if (contractStatus == DocumentStatus.SIGNED) {
            return new AreaContribution(AREA_KEY, NAME_KEY, 100, ReadinessState.DONE, List.of());
        }
        if (contractStatus == DocumentStatus.PENDING_SIGNATURES) {
            return new AreaContribution(
                    AREA_KEY,
                    NAME_KEY,
                    ReadinessMath.fallbackPercentage(ReadinessState.PARTIAL),
                    ReadinessState.PARTIAL,
                    List.of(new OutstandingItem(ITEM_PENDING, Map.of(), TARGET_TAB)));
        }
        // no Contract_Document, DRAFT, or VOID
        return new AreaContribution(
                AREA_KEY,
                NAME_KEY,
                0,
                ReadinessState.BLOCKED,
                List.of(new OutstandingItem(ITEM_NOT_READY, Map.of(), TARGET_TAB)));
    }
}
