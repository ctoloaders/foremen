package com.foremen.service.document;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.OfferDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.model.DocumentStatus;
import com.foremen.dao.model.OfferStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.dao.model.SignableDocumentTypeEntity;

/**
 * FOR-05-08 (Requirements 10.1, 10.2, 10.3, 10.4; design §Components {@code ProjectActivationSignal},
 * key decision — "Pricing/activation ownership"): the contract-signed &rarr; project activation
 * hand-off. When a {@code CONTRACT_*} {@link SignableDocumentEntity} reaches
 * {@link DocumentStatus#SIGNED} on a project whose offer is {@link OfferStatus#APPROVED}, it signals
 * the project eligible for {@code DRAFT &rarr; ACTIVE} and performs the activation transition (R10.1).
 *
 * <h2>Consumes, never re-implements (R10.2)</h2>
 * This signal <b>consumes</b> the FOR-05-07 / FOR-05-13 offer-approval outcome — it reads the
 * project's offer status through {@link OfferDao} and treats {@link OfferStatus#APPROVED} as the
 * gate — but it does <b>not</b> re-implement the estimate service-lock
 * ({@code DraftGate}/{@code EstimateStatus}) or the {@code OfferPriceSnapshot} price freeze, which
 * remain owned by FOR-05-07 / FOR-05-13. Here it only decides eligibility and flips the project
 * status.
 *
 * <h2>Gating &amp; idempotency</h2>
 * <ul>
 *   <li><b>Contract-gated (R10.1, R10.3):</b> only a document whose {@link SignableDocumentTypeEntity}
 *       {@code code} begins with the {@code CONTRACT_} prefix triggers anything; any non-contract
 *       document (or a contract document not yet {@code SIGNED}) is a no-op.</li>
 *   <li><b>Offer-gated (R10.1):</b> the project's offer must be {@code APPROVED}; otherwise no
 *       transition occurs.</li>
 *   <li><b>Idempotent (R10.4):</b> a project already {@link ProjectStatus#ACTIVE} (or past it) is left
 *       untouched, so re-signalling (e.g. a second contract signed, or a replay of the same signal)
 *       is a no-op.</li>
 * </ul>
 *
 * <p>Invoked synchronously from {@code SignableDocumentService.recomputeSignedState} on full-sign
 * (the moment the document's status becomes {@code SIGNED}), inside the same signing transaction, so
 * the activation is committed atomically with the signing (design §Generation + signing sequence).
 */
@Component
public class ProjectActivationSignal {

    /** The type-code prefix that identifies a contract document (R10.1, R10.3). */
    static final String CONTRACT_TYPE_PREFIX = "CONTRACT_";

    private final ProjectDao projectDao;
    private final OfferDao offerDao;

    public ProjectActivationSignal(ProjectDao projectDao, OfferDao offerDao) {
        this.projectDao = projectDao;
        this.offerDao = offerDao;
    }

    /**
     * Signals the owning project eligible for {@code DRAFT &rarr; ACTIVE} and performs the activation
     * transition <b>iff</b> {@code doc} is a {@code CONTRACT_*} document that has reached
     * {@link DocumentStatus#SIGNED} and the project's offer is {@link OfferStatus#APPROVED}
     * (Requirements 10.1, 10.3). Non-contract documents and un-signed contracts trigger nothing
     * (R10.3), and the call is idempotent — a project already {@link ProjectStatus#ACTIVE} (or beyond)
     * is left untouched (R10.4).
     *
     * <p>The offer gate is read from {@link OfferDao} (consuming the FOR-05-07 approval outcome, not
     * re-implementing it, R10.2). The activation is a single project-status write; the estimate lock
     * and price snapshot remain owned by FOR-05-07 / FOR-05-13.
     *
     * @param doc the document whose status just became {@code SIGNED}; a {@code null} document, a
     *            non-contract type, a non-{@code SIGNED} status, a missing project, a non-{@code
     *            APPROVED} offer, or an already-active project all make this a no-op
     */
    @Transactional
    public void onContractSigned(SignableDocumentEntity doc) {
        if (!isSignedContract(doc)) {
            return;
        }

        ProjectEntity project = doc.getProject();
        if (project == null || project.getId() == null) {
            return;
        }

        // Offer gate: consume the FOR-05-07 approval outcome (R10.1, R10.2).
        if (!hasApprovedOffer(project.getId())) {
            return;
        }

        // Idempotent: only a project not yet ACTIVE is eligible to be activated (R10.4).
        if (!isEligibleForActivation(project.getStatus())) {
            return;
        }

        project.setStatus(ProjectStatus.ACTIVE);
        projectDao.save(project);
    }

    /** A document is a signed contract iff its type code starts with {@code CONTRACT_} and it is {@code SIGNED}. */
    private boolean isSignedContract(SignableDocumentEntity doc) {
        if (doc == null || doc.getStatus() != DocumentStatus.SIGNED) {
            return false;
        }
        SignableDocumentTypeEntity type = doc.getDocumentType();
        String code = type != null ? type.getCode() : null;
        return code != null && code.startsWith(CONTRACT_TYPE_PREFIX);
    }

    /** Whether the project has at least one {@link OfferStatus#APPROVED} offer (the activation gate). */
    private boolean hasApprovedOffer(Long projectId) {
        return offerDao.findByProjectIdOrderByIdAsc(projectId).stream()
                .anyMatch(offer -> offer.getStatus() == OfferStatus.APPROVED);
    }

    /**
     * Whether a project in {@code current} status may still be activated. Only the pre-activation
     * stages ({@code DRAFT}/{@code READY_TO_OFFER}/{@code OFFERED}/{@code APPROVED}) are eligible;
     * {@code ACTIVE} and every later/terminal status is a no-op so re-signalling is idempotent (R10.4).
     */
    private boolean isEligibleForActivation(ProjectStatus current) {
        return current == ProjectStatus.DRAFT
                || current == ProjectStatus.READY_TO_OFFER
                || current == ProjectStatus.OFFERED
                || current == ProjectStatus.APPROVED;
    }
}
