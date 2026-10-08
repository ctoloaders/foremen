package com.foremen.service.document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import org.mockito.Mockito;

import com.foremen.dao.OfferDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.model.DocumentStatus;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.dao.model.SignableDocumentTypeEntity;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for the contract-signed &rarr; project activation hand-off implemented by
 * {@link ProjectActivationSignal#onContractSigned(SignableDocumentEntity)} (FOR-05-08, Requirements
 * 10.1, 10.3, 10.4; design §Components {@code ProjectActivationSignal}).
 *
 * <p>{@code onContractSigned} activates the owning project ({@code DRAFT}/{@code READY_TO_OFFER}/
 * {@code OFFERED}/{@code APPROVED} &rarr; {@link ProjectStatus#ACTIVE}) <b>iff all</b> of these gates
 * hold:
 * <ul>
 *   <li>the document's {@link SignableDocumentTypeEntity} {@code code} starts with the
 *       {@code CONTRACT_} prefix (contract-gated, R10.1/R10.3);</li>
 *   <li>the document status is {@link DocumentStatus#SIGNED} (R10.1);</li>
 *   <li>the project has at least one {@link OfferStatus#APPROVED} offer, read through
 *       {@link OfferDao} (offer-gated, consuming the FOR-05-07 outcome — R10.1/R10.2);</li>
 *   <li>the project is still in a pre-activation stage (R10.4 idempotency).</li>
 * </ul>
 * Any gate failing — a non-contract type, a non-{@code SIGNED} status, no {@code APPROVED} offer, or
 * an already-{@code ACTIVE}/later project — leaves the project status unchanged and performs no
 * {@code projectDao.save}. Re-invoking on an already-{@code ACTIVE} project is a no-op (idempotent).
 *
 * <p>The two DAO collaborators are mocked ({@link ProjectDao}, {@link OfferDao}); the document and
 * project graphs are purely in-memory, so the signal's gating logic is exercised directly with no
 * persistence or Spring context.
 *
 * <p>Feature: FOR-05-08-document-signing, Property 7: Contract-signed activation signal is idempotent
 * and contract/offer-gated.
 *
 * <p><b>Validates: Requirements 10.1, 10.3, 10.4</b>
 */
@Tag("Feature: FOR-05-08-document-signing, Property 7: Contract-signed activation signal is idempotent and contract/offer-gated")
class ProjectActivationSignalPropertyTest {

    private static final String CONTRACT_PREFIX = "CONTRACT_";

    /** The pre-activation stages from which the signal may flip the project to {@code ACTIVE} (R10.4). */
    private static final List<ProjectStatus> ELIGIBLE_STATUSES = List.of(
            ProjectStatus.DRAFT,
            ProjectStatus.READY_TO_OFFER,
            ProjectStatus.OFFERED,
            ProjectStatus.APPROVED);

    // ------------------------------------------------------------------------------------------
    // Property 7a: the project ends ACTIVE iff all four gates hold (contract type + SIGNED + an
    // APPROVED offer + a pre-activation stage); otherwise its status is left exactly as it was, and
    // projectDao.save is called iff (and only once when) the activation fires.
    // Validates: Requirements 10.1, 10.3, 10.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 500)
    @Tag("Feature: FOR-05-08-document-signing, Property 7: Contract-signed activation signal is idempotent and contract/offer-gated")
    void projectIsActivatedIffAllGatesHold(
            @ForAll("typeCodes") String typeCode,
            @ForAll("documentStatuses") DocumentStatus documentStatus,
            @ForAll("projectStatuses") ProjectStatus projectStatus,
            @ForAll("offerStatusSets") List<OfferStatus> offerStatuses) {

        ProjectDao projectDao = Mockito.mock(ProjectDao.class);
        OfferDao offerDao = Mockito.mock(OfferDao.class);
        ProjectActivationSignal signal = new ProjectActivationSignal(projectDao, offerDao);

        ProjectEntity project = project(1L, projectStatus);
        Mockito.when(offerDao.findByProjectIdOrderByIdAsc(1L))
                .thenReturn(offersWithStatuses(project, offerStatuses));

        SignableDocumentEntity doc = document(project, typeCode, documentStatus);

        boolean contractGate = typeCode.startsWith(CONTRACT_PREFIX);
        boolean signedGate = documentStatus == DocumentStatus.SIGNED;
        boolean offerGate = offerStatuses.contains(OfferStatus.APPROVED);
        boolean stageGate = ELIGIBLE_STATUSES.contains(projectStatus);
        boolean shouldActivate = contractGate && signedGate && offerGate && stageGate;

        signal.onContractSigned(doc);

        if (shouldActivate) {
            assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
            Mockito.verify(projectDao, Mockito.times(1)).save(project);
        } else {
            assertThat(project.getStatus()).isEqualTo(projectStatus);
            Mockito.verify(projectDao, Mockito.never()).save(Mockito.any());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 7b: re-invoking the signal is idempotent. For a signed contract on a project that has
    // an APPROVED offer, the first call activates it (DRAFT/READY_TO_OFFER/OFFERED/APPROVED → ACTIVE);
    // a second call never changes the now-ACTIVE project and never calls projectDao.save again.
    // Validates: Requirements 10.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 300)
    @Tag("Feature: FOR-05-08-document-signing, Property 7: Contract-signed activation signal is idempotent and contract/offer-gated")
    void reinvokingIsIdempotent(
            @ForAll("eligibleProjectStatuses") ProjectStatus projectStatus,
            @ForAll("contractTypeCodes") String contractTypeCode) {

        ProjectDao projectDao = Mockito.mock(ProjectDao.class);
        OfferDao offerDao = Mockito.mock(OfferDao.class);
        ProjectActivationSignal signal = new ProjectActivationSignal(projectDao, offerDao);

        ProjectEntity project = project(1L, projectStatus);
        Mockito.when(offerDao.findByProjectIdOrderByIdAsc(1L))
                .thenReturn(offersWithStatuses(project, List.of(OfferStatus.APPROVED)));

        SignableDocumentEntity doc = document(project, contractTypeCode, DocumentStatus.SIGNED);

        // First signal: activates the eligible project.
        signal.onContractSigned(doc);
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);

        // Second signal (replay / another contract signed): a no-op on the already-ACTIVE project.
        signal.onContractSigned(doc);

        assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        // save fired exactly once across both calls — the second call did not persist again.
        Mockito.verify(projectDao, Mockito.times(1)).save(project);
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    private ProjectEntity project(Long id, ProjectStatus status) {
        ProjectEntity project = new ProjectEntity();
        project.setId(id);
        project.setStatus(status);
        return project;
    }

    private SignableDocumentEntity document(ProjectEntity project, String typeCode, DocumentStatus status) {
        SignableDocumentTypeEntity type = new SignableDocumentTypeEntity();
        type.setCode(typeCode);

        SignableDocumentEntity doc = new SignableDocumentEntity();
        doc.setProject(project);
        doc.setDocumentType(type);
        doc.setStatus(status);
        return doc;
    }

    private List<OfferEntity> offersWithStatuses(ProjectEntity project, List<OfferStatus> statuses) {
        return statuses.stream()
                .map(status -> {
                    OfferEntity offer = new OfferEntity();
                    offer.setProject(project);
                    offer.setStatus(status);
                    return offer;
                })
                .toList();
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /**
     * Arbitrary type codes mixing contract codes ({@code CONTRACT_*}) and non-contract codes, so the
     * contract prefix gate is exercised from both sides. Includes near-miss non-contract codes
     * ({@code OFFER_}, {@code CONTRACTOR}, {@code contract_lower}) that must NOT match the exact
     * {@code CONTRACT_} prefix.
     */
    @Provide
    Arbitrary<String> typeCodes() {
        return Arbitraries.oneOf(contractTypeCodes(), nonContractTypeCodes());
    }

    /** Contract type codes — every one starts with the exact {@code CONTRACT_} prefix. */
    @Provide
    Arbitrary<String> contractTypeCodes() {
        return Arbitraries.of(
                "CONTRACT_",
                "CONTRACT_MAIN",
                "CONTRACT_ANNEX",
                "CONTRACT_SUBCONTRACTOR",
                "CONTRACT_X");
    }

    /** Non-contract type codes, including deliberate near-misses that must not satisfy the prefix gate. */
    @Provide
    Arbitrary<String> nonContractTypeCodes() {
        return Arbitraries.of(
                "OFFER",
                "OFFER_MAIN",
                "ACT_HANDOVER",
                "INVOICE",
                "CONTRACTOR",       // no underscore after CONTRACT — must not match
                "contract_lower",   // wrong case — must not match
                "PRE_CONTRACT_X",   // prefix not at start — must not match
                "WARRANTY");
    }

    /** The full {@link DocumentStatus} space, so only the {@code SIGNED} value opens the status gate. */
    @Provide
    Arbitrary<DocumentStatus> documentStatuses() {
        return Arbitraries.of(DocumentStatus.class);
    }

    /** The full {@link ProjectStatus} space, so eligible and ineligible stages are both covered. */
    @Provide
    Arbitrary<ProjectStatus> projectStatuses() {
        return Arbitraries.of(ProjectStatus.class);
    }

    /** Only the four pre-activation stages the signal may flip to {@code ACTIVE}. */
    @Provide
    Arbitrary<ProjectStatus> eligibleProjectStatuses() {
        return Arbitraries.of(
                ProjectStatus.DRAFT,
                ProjectStatus.READY_TO_OFFER,
                ProjectStatus.OFFERED,
                ProjectStatus.APPROVED);
    }

    /**
     * Arbitrary offer-status sets (0..5 offers) over the full {@link OfferStatus} space, so projects
     * with and without an {@code APPROVED} offer — and with multiple offers of mixed status — all
     * exercise the offer gate.
     */
    @Provide
    Arbitrary<List<OfferStatus>> offerStatusSets() {
        return Arbitraries.of(OfferStatus.class).list().ofMinSize(0).ofMaxSize(5);
    }
}
