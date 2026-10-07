package com.foremen.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.AdminDao;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.EstimateLineRoomMaterialDao;
import com.foremen.dao.OfferDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.OfferAction;
import com.foremen.dao.model.OfferDiscountEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.estimate.DraftGateGuard;
import com.foremen.service.estimate.EstimateAssignmentService;
import com.foremen.service.estimate.EstimateAssignmentService.StagedEdit;
import com.foremen.service.model.OfferServiceExtendedModel;
import com.foremen.service.model.OfferServiceModel;
import com.foremen.service.model.mapper.OfferServiceMapper;
import com.foremen.service.offer.AgreedOfferView;
import com.foremen.service.offer.ClientOfferReadModelAssembler;
import com.foremen.service.offer.DiscountResolver;
import com.foremen.service.offer.DiscountResolver.DiscountInput;
import com.foremen.service.offer.DiscountResolver.EffectiveDiscount;
import com.foremen.service.offer.DiscountResolver.EstimateLineInput;
import com.foremen.service.offer.OfferStateChangeEvent;
import com.foremen.service.offer.OfferStatusMachine;
import com.foremen.service.offer.OfferTotalsCalculator;
import com.foremen.service.offer.OfferTotalsCalculator.OfferTotals;

import jakarta.persistence.EntityManager;

/**
 * Project-scoped lifecycle service for {@link OfferEntity} — the offer preparation and
 * client-negotiation stage (FOR-05-07, Requirements 1.1, 1.2, 1.5, 1.6, 1.7, 3.2, 3.5, 3.7, 5.4,
 * 7.1, 7.3, 20.1; design §OfferService).
 *
 * <p>Following the FOR-03-04a single-contract shape (mirrors {@link EstimateService}), it implements
 * exactly one CRUD contract — {@link ProjectScopedService} — supplies the standard CRUD plumbing, the
 * single mandatory per-entity override {@link #getProjectIdPath()} &rarr; {@code "project.id"}
 * (R1.1), and wires {@link #allowedProjectIds(Long)} to {@link ProjectAccessCache}.
 *
 * <h2>Lifecycle methods (task 5.1)</h2>
 * <ul>
 *   <li>{@link #prepareOffer(Long)} — prepares an offer from the project's estimate in <b>any</b>
 *       status (pricing is no longer a precondition, R1.1/R1.2); the only prepare guards are entity
 *       existence (404) and the single-active-offer rule — a second non-terminal offer is rejected
 *       (409 {@code error.offer.active.exists}); creates {@code Offer{status=DRAFT, revision=1}}, seeds
 *       {@code selectedPackage} from {@code estimate.appliedPackageCode} (null-safe, R1.7), and
 *       computes totals from the live-referenced estimate prices (R1.3).</li>
 *   <li>{@link #selectPackage(Long, String)} — persists the selection, re-derives the finishing
 *       selection through the FOR-05-05 propagation write path, recomputes totals, bumps the revision
 *       when the priced proposal changes (R1.5). Asserts the estimate is not service-locked via
 *       {@link DraftGateGuard} (R20.1).</li>
 *   <li>{@link #send(Long)} — {@code DRAFT → SENT}; project {@code READY_TO_OFFER → OFFERED} (R3.2).</li>
 *   <li>{@link #withdraw(Long)} — any non-terminal offer {@code → WITHDRAWN} (R3.7).</li>
 *   <li>{@link #approve(Long)} — {@code SENT}/{@code COUNTERED → APPROVED}; project
 *       {@code OFFERED → APPROVED}; records the {@code Agreed_Offer_Version}; publishes an
 *       {@link OfferStateChangeEvent} downstream consumers (FOR-05-14/09) read (R3.5, R7.1).</li>
 * </ul>
 *
 * <p>Every status transition is delegated to the pure {@link OfferStatusMachine} (R3.8 / R10.5); the
 * service never assigns a status directly. The acting role fed to the machine is resolved from the
 * security context (ADMIN via authorities, otherwise the user's role code by numeric principal id).
 */
@Service
public class OfferService
        implements ProjectScopedService<OfferServiceModel, OfferServiceExtendedModel, OfferEntity, Long> {

    /** 409 when a project already has a non-terminal (active) offer (R1.4). */
    static final String ACTIVE_OFFER_EXISTS_MESSAGE = "error.offer.active.exists";

    /** 404 when the referenced offer / project / estimate cannot be resolved. */
    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    /**
     * 409 for a rejected mutation on a terminal offer — the same localized code the
     * {@link OfferStatusMachine} raises for an illegal transition (R3.9 / R10.5), so a terminal-offer
     * write and an illegal transition are indistinguishable to the client.
     */
    static final String ILLEGAL_TRANSITION_MESSAGE = "error.offer.illegal.transition";

    /**
     * 400 when a client material write targets a non-{@code finishing} line — a construction material
     * line. The CLIENT may only fill a finishing Placeholder (R5.8 / R11.4).
     */
    static final String MATERIAL_NOT_FINISHING_MESSAGE = "error.offer.material.not.finishing";

    /**
     * 400 when a client material write targets a finishing line that already has a chosen concrete
     * product (not a Placeholder). Only an unfilled Placeholder may be filled (R11.3 / R11.4).
     */
    static final String MATERIAL_NOT_PLACEHOLDER_MESSAGE = "error.offer.material.not.placeholder";

    /** The three terminal statuses — an offer in any of these is inactive (R3.1). */
    private static final List<OfferStatus> TERMINAL_STATUSES =
            List.of(OfferStatus.APPROVED, OfferStatus.REJECTED, OfferStatus.WITHDRAWN);

    private final OfferDao offerDao;
    private final EstimateDao estimateDao;
    private final EstimateLineRoomMaterialDao estimateLineRoomMaterialDao;
    private final OfferPackageDao offerPackageDao;
    private final UserDao userDao;
    private final OfferServiceMapper offerServiceMapper;
    private final ProjectAccessCache projectAccessCache;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final OfferStatusMachine offerStatusMachine;
    private final OfferTotalsCalculator offerTotalsCalculator;
    private final DiscountResolver discountResolver;
    private final DraftGateGuard draftGateGuard;
    private final EstimateAssignmentService estimateAssignmentService;
    private final ApplicationEventPublisher eventPublisher;
    private final ClientOfferReadModelAssembler clientOfferReadModelAssembler;

    public OfferService(OfferDao offerDao,
                        EstimateDao estimateDao,
                        EstimateLineRoomMaterialDao estimateLineRoomMaterialDao,
                        OfferPackageDao offerPackageDao,
                        UserDao userDao,
                        OfferServiceMapper offerServiceMapper,
                        ProjectAccessCache projectAccessCache,
                        AuditLogDao auditLogDao,
                        EntityManager entityManager,
                        OfferStatusMachine offerStatusMachine,
                        OfferTotalsCalculator offerTotalsCalculator,
                        DiscountResolver discountResolver,
                        DraftGateGuard draftGateGuard,
                        EstimateAssignmentService estimateAssignmentService,
                        ApplicationEventPublisher eventPublisher,
                        ClientOfferReadModelAssembler clientOfferReadModelAssembler) {
        this.offerDao = offerDao;
        this.estimateDao = estimateDao;
        this.estimateLineRoomMaterialDao = estimateLineRoomMaterialDao;
        this.offerPackageDao = offerPackageDao;
        this.userDao = userDao;
        this.offerServiceMapper = offerServiceMapper;
        this.projectAccessCache = projectAccessCache;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.offerStatusMachine = offerStatusMachine;
        this.offerTotalsCalculator = offerTotalsCalculator;
        this.discountResolver = discountResolver;
        this.draftGateGuard = draftGateGuard;
        this.estimateAssignmentService = estimateAssignmentService;
        this.eventPublisher = eventPublisher;
        this.clientOfferReadModelAssembler = clientOfferReadModelAssembler;
    }

    // --- CRUD plumbing (inherited from AdminService via ProjectScopedService) ---

    @Override
    public AdminDao<OfferEntity, Long> getDao() {
        return offerDao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<OfferEntity, OfferServiceModel, OfferServiceExtendedModel> getMapper() {
        return offerServiceMapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<OfferEntity> getDaoModelClass() {
        return OfferEntity.class;
    }

    // --- ProjectScopedService overrides ---

    /** The offer resolves its project boundary through its {@code @ManyToOne project} FK (R1.1). */
    @Override
    public String getProjectIdPath() {
        return "project.id";
    }

    /** Wires the allowed-project-ids lookup to the production cache. */
    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccessCache.get(userId);
    }

    // --- Lifecycle: prepare (R1.1, R1.2, R1.3, R1.6, R1.7) ---

    /**
     * Prepares a new {@code Offer} for {@code projectId} from its {@code PRICED} estimate.
     *
     * <p>Preparation works from an estimate in <b>any</b> status — pricing is no longer a
     * precondition (R1.1/R1.2); readiness is always visible rather than gating preparation.
     *
     * <ol>
     *   <li>Resolves the project's estimate; if the project has no estimate the request is rejected
     *       with {@code 404 error.entity.not.found}.</li>
     *   <li>Rejects a second active offer: if the project already has a non-terminal offer, throws
     *       {@code 409 error.offer.active.exists} (R1.4).</li>
     *   <li>Creates {@code Offer{status=DRAFT, revision=1}} linked to the project and estimate
     *       (R1.1).</li>
     *   <li>Seeds {@code selectedPackage} from {@code estimate.appliedPackageCode} resolved to an
     *       {@link OfferPackageEntity}; null-safe — an unset or unknown code yields
     *       {@code selectedPackage = null} without failing creation (R1.6/R1.7).</li>
     *   <li>Computes the totals from the live-referenced estimate client-facing prices with no
     *       discounts yet (R1.3).</li>
     * </ol>
     *
     * @param projectId the owning project id
     * @return the persisted offer entity
     * @throws ForemenApiException 404 when the project has no estimate; 409 when an active offer
     *                             already exists
     */
    @Transactional
    public OfferEntity prepareOffer(Long projectId) {
        EstimateEntity estimate = estimateDao.findByProjectId(projectId)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "projectId", projectId));

        // R1.1/R1.2: preparation works from an estimate in ANY status (no PRICED precondition).

        // R1.4: at most one non-terminal offer per project.
        if (!offerDao.findByProjectIdAndStatusNotIn(projectId, TERMINAL_STATUSES).isEmpty()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ACTIVE_OFFER_EXISTS_MESSAGE);
        }

        OfferEntity offer = new OfferEntity();
        offer.setProject(estimate.getProject());
        offer.setEstimate(estimate);
        offer.setStatus(OfferStatus.DRAFT);
        offer.setRevision(1);
        // R1.6/R1.7: seed the package from the estimate's applied package, null-safe.
        offer.setSelectedPackage(resolveAppliedPackageOrNull(estimate));

        recomputeTotals(offer);

        OfferEntity saved = offerDao.save(offer);
        entityManager.flush();
        return saved;
    }

    // --- Lifecycle: select package (R1.5, R20.1) ---

    /**
     * Sets the offer's {@code selectedPackage} to the package identified by {@code packageCode},
     * re-derives the offer's finishing-material selection through the FOR-05-05 package propagation
     * write path, recomputes the totals, and bumps the revision when the priced proposal changes
     * (R1.5).
     *
     * <p>Because this write touches the package / finishing selection it first asserts the offer is
     * non-terminal (R3.9) and the estimate is not service-locked via
     * {@link DraftGateGuard#assertDraft(EstimateEntity)} (409 {@code error.estimate.locked}, R20.1).
     *
     * @param offerId     the offer to change
     * @param packageCode the target package code; {@code null}/blank/unknown clears the selection
     *                    (null-safe, mirroring R1.7)
     * @return the updated offer entity
     * @throws ForemenApiException 404 when the offer is missing; 409 when the offer is terminal or the
     *                             estimate is service-locked
     */
    @Transactional
    public OfferEntity selectPackage(Long offerId, String packageCode) {
        OfferEntity offer = resolveOffer(offerId);
        assertNonTerminal(offer);

        EstimateEntity estimate = offer.getEstimate();
        // R20.1: package/finishing writes are service-locked once the estimate is past DRAFT.
        draftGateGuard.assertDraft(estimate);

        OfferPackageEntity previousPackage = offer.getSelectedPackage();
        OfferPackageEntity newPackage = resolvePackageOrNull(packageCode);
        offer.setSelectedPackage(newPackage);

        // Re-derive the finishing selection from the package via the FOR-05-05 propagation write path
        // (delegates to EstimateAssignmentService; itself DRAFT-gated). A blank/unknown package makes
        // no propagation.
        if (packageCode != null && !packageCode.isBlank()) {
            estimateAssignmentService.applyAssignments(
                    estimate.getProject().getId(),
                    List.of(StagedEdit.mergePackageMaterials(packageCode)));
        }

        recomputeTotals(offer);

        // R1.5: bump the revision when the priced proposal (selected package) actually changed.
        if (!samePackage(previousPackage, newPackage)) {
            offer.setRevision(offer.getRevision() == null ? 1 : offer.getRevision() + 1);
        }

        offerDao.save(offer);
        entityManager.flush();
        return offer;
    }

    // --- Client finishing-material selection (R5.8, R11.3–R11.5, R11.7) ---

    /**
     * The CLIENT-scoped, offer-stage, finishing-Placeholder-only invocation of the FOR-05-05
     * {@code chooseConcrete} write path (design §ClientOfferController, R11.3/R11.4/R11.5/R11.7). It
     * lets a CLIENT (their own project, ABAC-scoped) — or a MANAGER/ADMIN — pick a concrete finishing
     * product for one finishing Placeholder slot of the offer, reusing the existing estimate write
     * path rather than introducing a new material write.
     *
     * <p>Gating applied here, on top of what the reused write path already enforces:
     * <ol>
     *   <li><b>Offer non-terminal + negotiable</b> — the write is permitted only while the offer is
     *       {@code SENT}/{@code CHANGES_REQUESTED}/{@code COUNTERED} (the {@code ON_APPROVAL} window):
     *       {@link #assertNonTerminal(OfferEntity)} then {@link #assertNegotiable(OfferEntity)} (R11.7 /
     *       Property 8). The estimate-{@code DRAFT} half of R11.7 is enforced by the reused
     *       {@code EstimateAssignmentService.chooseConcrete} via {@code DraftGateGuard}.</li>
     *   <li><b>Line belongs to this offer's estimate</b> — the material line's owning estimate must be
     *       the offer's referenced estimate (else 404, indistinguishable from a missing line, matching
     *       the estimate service convention).</li>
     *   <li><b>Finishing branch</b> — a construction line is rejected {@code 400
     *       error.offer.material.not.finishing} (R5.8 / R11.4); the CLIENT may fill only a finishing
     *       Placeholder.</li>
     *   <li><b>Placeholder</b> — a finishing line that already carries a chosen concrete product is
     *       rejected {@code 400 error.offer.material.not.placeholder} (R11.3 / R11.4); only an unfilled
     *       Placeholder may be filled.</li>
     * </ol>
     *
     * <p>Every other client material write (construction, works, volumes, prices, or an already-chosen
     * finishing line) is thereby rejected server-side (R5.8 / R11.4 / Property 6); the write path is
     * confined to the caller's own project by the {@code OFFERS} ABAC project scope at the controller.
     * After the delegated {@code chooseConcrete} collapses the line to its chosen product price, the
     * offer totals are recomputed over the live-referenced estimate prices (R1.3) and the offer
     * revision is bumped because the priced proposal changed (R11.2, mirroring {@link #selectPackage}).
     *
     * @param offerId        the offer whose finishing Placeholder is being filled
     * @param materialLineId the target finishing material line (must be a Placeholder of the offer's
     *                       estimate)
     * @param materialId     the chosen concrete finishing product id
     * @return the updated offer entity (with recomputed totals and bumped revision)
     * @throws ForemenApiException 404 when the offer / line is missing or the line is not in the
     *                             offer's estimate; 409 when the offer is terminal or not negotiable;
     *                             400 when the line is not a finishing Placeholder
     */
    @Transactional
    public OfferEntity chooseFinishingConcrete(Long offerId, Long materialLineId, Long materialId) {
        OfferEntity offer = resolveOffer(offerId);
        // R11.7 / Property 8: only a non-terminal, negotiable offer accepts a client material choice.
        assertNonTerminal(offer);
        assertNegotiable(offer);

        EstimateEntity estimate = offer.getEstimate();
        EstimateLineRoomMaterialEntity line = resolveMaterialLineInEstimate(materialLineId, estimate);

        // R5.8 / R11.4: the CLIENT may write ONLY a finishing Placeholder — reject any other line.
        if (line.getBranch() != ConsumptionBranch.finishing) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, MATERIAL_NOT_FINISHING_MESSAGE);
        }
        // R11.3 / R11.4: a finishing line with a chosen concrete product is no longer a Placeholder.
        if (line.getConcreteFinishingMaterial() != null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, MATERIAL_NOT_PLACEHOLDER_MESSAGE);
        }

        // Delegate to the EXISTING FOR-05-05 write path (itself DRAFT-gated via DraftGateGuard); it
        // sets the concrete finishing product, collapses the line to its retailNet, and recomputes the
        // estimate. The offer stores no per-line copy, so the offer totals follow from the live
        // estimate prices (R1.3 / R19.4).
        estimateAssignmentService.chooseConcrete(
                estimate.getProject().getId(), materialLineId, materialId);

        recomputeTotals(offer);
        // R11.2: the priced proposal changed -> bump the offer revision (mirrors selectPackage).
        offer.setRevision(offer.getRevision() == null ? 1 : offer.getRevision() + 1);

        offerDao.save(offer);
        entityManager.flush();
        return offer;
    }

    // --- Lifecycle: send (R3.2) ---

    /**
     * Sends a {@code DRAFT} offer to the client: transitions the offer {@code DRAFT → SENT} (its
     * derived visibility becomes {@code ON_APPROVAL}) and moves the project
     * {@code READY_TO_OFFER → OFFERED} (R3.2). The offer status transition is validated by
     * {@link OfferStatusMachine}.
     *
     * @param offerId the offer to send
     * @return the updated offer entity
     * @throws ForemenApiException 404 when the offer is missing; 409 on an illegal transition
     */
    @Transactional
    public OfferEntity send(Long offerId) {
        OfferEntity offer = resolveOffer(offerId);
        transitionStatus(offer, OfferAction.SEND);
        // R3.2: drive the project READY_TO_OFFER -> OFFERED alongside the offer send.
        advanceProjectStatus(offer.getProject(), ProjectStatus.READY_TO_OFFER, ProjectStatus.OFFERED);
        offerDao.save(offer);
        entityManager.flush();
        return offer;
    }

    // --- Lifecycle: withdraw (R3.7) ---

    /**
     * Withdraws any non-terminal offer, transitioning it {@code → WITHDRAWN} (R3.7). The transition
     * is validated by {@link OfferStatusMachine} (a terminal offer is rejected with 409).
     *
     * @param offerId the offer to withdraw
     * @return the updated offer entity
     * @throws ForemenApiException 404 when the offer is missing; 409 on an illegal transition
     */
    @Transactional
    public OfferEntity withdraw(Long offerId) {
        OfferEntity offer = resolveOffer(offerId);
        transitionStatus(offer, OfferAction.WITHDRAW);
        offerDao.save(offer);
        entityManager.flush();
        return offer;
    }

    // --- Lifecycle: reject (R3.6) ---

    /**
     * Rejects a {@code SENT}/{@code COUNTERED} offer on behalf of the CLIENT, transitioning it
     * {@code → REJECTED} (R3.6). The transition (and the acting-role gate) is validated by
     * {@link OfferStatusMachine}, which rejects an illegal triple — including any exit from a terminal
     * state — with 409 {@code error.offer.illegal.transition}. The project status is left unchanged (a
     * rejected offer does not itself move the project out of {@code OFFERED}).
     *
     * @param offerId the offer to reject
     * @return the updated offer entity
     * @throws ForemenApiException 404 when the offer is missing; 409 on an illegal transition
     */
    @Transactional
    public OfferEntity reject(Long offerId) {
        OfferEntity offer = resolveOffer(offerId);
        transitionStatus(offer, OfferAction.REJECT);
        offerDao.save(offer);
        entityManager.flush();
        return offer;
    }

    // --- Lifecycle: approve (R3.5, R7.1) ---

    /**
     * Approves a {@code SENT}/{@code COUNTERED} offer: transitions it {@code → APPROVED}, moves the
     * project {@code OFFERED → APPROVED} (R3.5), records the current revision as the
     * {@code Agreed_Offer_Version} ({@code approvedRevision}, R7.1), and publishes an
     * {@link OfferStateChangeEvent} that downstream consumers (FOR-05-14 price freeze, FOR-05-09
     * contract) read. The offer status transition is validated by {@link OfferStatusMachine}.
     *
     * @param offerId the offer to approve
     * @return the updated offer entity
     * @throws ForemenApiException 404 when the offer is missing; 409 on an illegal transition
     */
    @Transactional
    public OfferEntity approve(Long offerId) {
        OfferEntity offer = resolveOffer(offerId);
        OfferStatus previous = offer.getStatus();
        transitionStatus(offer, OfferAction.APPROVE);
        // R3.5: drive the project OFFERED -> APPROVED alongside the offer approval.
        advanceProjectStatus(offer.getProject(), ProjectStatus.OFFERED, ProjectStatus.APPROVED);
        // R7.1: record the Agreed_Offer_Version.
        offer.setApprovedRevision(offer.getRevision());
        offerDao.save(offer);
        entityManager.flush();

        // Emit the state-change event downstream consumers (FOR-05-14/09) read.
        eventPublisher.publishEvent(new OfferStateChangeEvent(
                offer.getId(),
                offer.getProject() != null ? offer.getProject().getId() : null,
                offer.getEstimate() != null ? offer.getEstimate().getId() : null,
                previous,
                offer.getStatus(),
                offer.getApprovedRevision()));
        return offer;
    }

    // --- Agreed-version read model (R7.2, R7.4, R10.8) ---

    /**
     * The {@code Agreed_Offer_Version} view of an {@code APPROVED} offer — the read model FOR-05-14
     * (price freeze) and FOR-05-09 (contract) consume (R7.4). It carries the approved revision's
     * totals, the selected package, and the applied discounts, and holds no cost/margin/worker-rate/
     * estimate-unit-price field (assembled by {@link ClientOfferReadModelAssembler#toAgreedView}).
     *
     * <p><b>Stability guarantee (R7.2 / R10.8).</b> The view is only served for an offer whose status
     * is {@code APPROVED}. Because {@code APPROVED} is terminal, every mutator rejects it
     * ({@link #assertNonTerminal(OfferEntity)}, and the {@link OfferStatusMachine} allows no exit from
     * a terminal state), so the totals, selected package, package-derived finishing selection, and
     * applied discounts frozen at approval never change afterwards — the assembled view is stable
     * across repeated reads.
     *
     * @param offerId the offer id
     * @return the agreed-version view of the approved offer
     * @throws ForemenApiException 404 when the offer is missing; 409
     *                             {@code error.offer.illegal.transition} when the offer is not yet
     *                             {@code APPROVED} (no agreed version exists to serve)
     */
    @Transactional(readOnly = true)
    public AgreedOfferView agreedOfferView(Long offerId) {
        return clientOfferReadModelAssembler.toAgreedView(resolveApprovedOffer(offerId));
    }

    /**
     * The {@code Agreed_Offer_Version} view of a project's {@code APPROVED} offer, resolved by project
     * (R7.4) — the by-project entry point downstream consumers use when they hold the project id
     * rather than the offer id. Carries the same stability guarantee as {@link #agreedOfferView(Long)}.
     *
     * @param projectId the owning project id
     * @return the agreed-version view of the project's approved offer
     * @throws ForemenApiException 404 when the project has no {@code APPROVED} offer
     */
    @Transactional(readOnly = true)
    public AgreedOfferView agreedOfferViewByProject(Long projectId) {
        OfferEntity approved = offerDao.findByProjectIdOrderByIdAsc(projectId).stream()
                .filter(o -> o.getStatus() == OfferStatus.APPROVED)
                .reduce((first, second) -> second) // the latest approved offer of the project
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "projectId", projectId));
        return clientOfferReadModelAssembler.toAgreedView(approved);
    }

    // --- public read helpers (consumed by controllers / sibling services) ---

    /**
     * Loads the managed offer entity by id (404 when missing) — the public read entry point sibling
     * write paths reuse when they hold an offer id and need the managed {@link OfferEntity} (its
     * {@code project} / {@code estimate} live references, its status) before applying their own gate.
     * It is the exact resolver {@link #prepareOffer}/{@link #send}/{@link #approve} use internally, so
     * callers see the same 404 {@code error.entity.not.found} shape.
     *
     * <p>Used by {@code ClientOfferController} (task 9.2) to resolve the offer whose finishing
     * Placeholder a CLIENT/MANAGER/ADMIN fills, so it can assert the offer is non-terminal
     * ({@link #assertNonTerminal(OfferEntity)}) and negotiable ({@link #assertNegotiable(OfferEntity)})
     * before delegating to the FOR-05-05 {@code chooseConcrete} write path.
     *
     * @param offerId the offer id
     * @return the managed offer entity
     * @throws ForemenApiException 404 {@code error.entity.not.found} when the offer is missing
     */
    @Transactional(readOnly = true)
    public OfferEntity getOffer(Long offerId) {
        return resolveOffer(offerId);
    }

    /**
     * Resolves a project's <b>current</b> offer by project id — the by-project read entry point the
     * Offer tab uses on a fresh page load, when it holds the project id rather than an offer id and
     * cannot otherwise discover the project's existing offer (the only prior offer-id source was the
     * in-memory result of a successful {@code prepareOffer}).
     *
     * <p>Resolution order:
     * <ol>
     *   <li>the single non-terminal (active) offer if one exists
     *       ({@code findByProjectIdAndStatusNotIn(projectId, TERMINAL_STATUSES)} — the one-active-offer
     *       invariant, R1.4, normally yields at most one; if more than one somehow exists, the latest
     *       by id is taken);</li>
     *   <li>otherwise the latest offer overall ({@code findByProjectIdOrderByIdAsc} &rarr; last) — e.g.
     *       the most recent terminal offer when the project has no active one;</li>
     *   <li>{@link Optional#empty()} when the project has NO offer at all.</li>
     * </ol>
     *
     * <p>Unlike the by-id read it does not apply the client-visibility gate — the caller
     * (the controller) applies {@link OfferVisibilityResolver#assertClientVisible} on the resolved
     * offer for a CLIENT, so a DRAFT-visibility (not-yet-sent) offer still stays hidden (R5.9/R17.5).
     *
     * @param projectId the owning project id
     * @return the project's current offer, or empty when the project has no offer
     */
    @Transactional(readOnly = true)
    public Optional<OfferEntity> getCurrentOfferByProject(Long projectId) {
        // R1.4: prefer the single active (non-terminal) offer; defensively take the latest by id if
        // the invariant were ever violated.
        Optional<OfferEntity> active = offerDao.findByProjectIdAndStatusNotIn(projectId, TERMINAL_STATUSES)
                .stream()
                .reduce((first, second) -> second);
        if (active.isPresent()) {
            return active;
        }
        // Otherwise fall back to the latest offer overall (e.g. the most recent terminal offer).
        return offerDao.findByProjectIdOrderByIdAsc(projectId).stream()
                .reduce((first, second) -> second);
    }

    /**
     * Asserts the offer is in a <b>negotiable</b> state — one of {@code SENT},
     * {@code CHANGES_REQUESTED}, {@code COUNTERED} (the client-visible {@code ON_APPROVAL} window, per
     * the {@code OfferVisibilityResolver} mapping). A {@code DRAFT} offer is not yet negotiable and a
     * terminal offer is no longer negotiable, so a client finishing-material selection is permitted
     * only in these three states (R11.7 / Property 8). The estimate-{@code DRAFT} half of that gate is
     * enforced by the reused {@code EstimateAssignmentService.chooseConcrete} write path
     * ({@code DraftGateGuard}), so callers need only add this offer-negotiability gate.
     *
     * @param offer the offer about to receive a client material selection; must be non-null
     * @throws ForemenApiException 409 {@code error.offer.illegal.transition} when the offer is not in a
     *                             negotiable state
     */
    public void assertNegotiable(OfferEntity offer) {
        OfferStatus status = offer.getStatus();
        if (status != OfferStatus.SENT
                && status != OfferStatus.CHANGES_REQUESTED
                && status != OfferStatus.COUNTERED) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ILLEGAL_TRANSITION_MESSAGE);
        }
    }

    // --- helpers ---

    /** Loads the managed offer entity or 404s. */
    private OfferEntity resolveOffer(Long offerId) {
        return offerDao.findById(offerId)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "offerId", offerId));
    }

    /**
     * Loads a material line by id and asserts it belongs to {@code estimate} (the offer's referenced
     * estimate). A missing line, or a line owned by a different estimate, is rejected 404
     * {@code error.entity.not.found} — indistinguishable from a missing line, matching the estimate
     * service convention and confining a client material write to its own offer's estimate (R11.4).
     */
    private EstimateLineRoomMaterialEntity resolveMaterialLineInEstimate(
            Long materialLineId, EstimateEntity estimate) {
        EstimateLineRoomMaterialEntity material = materialLineId == null ? null
                : estimateLineRoomMaterialDao.findById(materialLineId).orElse(null);
        EstimateLineRoomQtyEntity roomQty = material != null ? material.getRoomQty() : null;
        EstimateLineEntity line = roomQty != null ? roomQty.getLine() : null;
        EstimateEntity owning = line != null ? line.getEstimate() : null;
        if (owning == null || estimate == null || owning.getId() == null
                || !owning.getId().equals(estimate.getId())) {
            throw new ForemenApiException(
                    HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "materialLineId", materialLineId);
        }
        return material;
    }

    /**
     * Loads the offer and asserts it is {@code APPROVED} before serving its agreed-version view. A
     * non-{@code APPROVED} offer has no {@code Agreed_Offer_Version} to expose, so it is rejected with
     * the same 409 {@code error.offer.illegal.transition} code used for terminal-state guarding.
     */
    private OfferEntity resolveApprovedOffer(Long offerId) {
        OfferEntity offer = resolveOffer(offerId);
        if (offer.getStatus() != OfferStatus.APPROVED) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ILLEGAL_TRANSITION_MESSAGE);
        }
        return offer;
    }

    /**
     * Rejects a mutation on a terminal offer with the same 409 the status machine uses (R3.9). This
     * is the <b>shared terminal-immutability guard</b> every offer mutator must call before writing:
     * {@link #selectPackage(Long, String)} calls it directly, and the sibling write services added in
     * tasks 6/7 ({@code OfferDiscountService}, {@code NegotiationService}) reuse it so the
     * terminal-immutability invariant (R3.9 / R10.4 / Property 5) holds uniformly across every write
     * path — including any mutator those services add — not just the ones that route through
     * {@link OfferStatusMachine}. Mutators that go through the status machine (send/withdraw/approve)
     * are additionally guarded by the machine, which allows no exit from a terminal state.
     *
     * @param offer the offer about to be mutated; must be non-null
     * @throws ForemenApiException 409 {@code error.offer.illegal.transition} when the offer is terminal
     */
    public void assertNonTerminal(OfferEntity offer) {
        if (offer.getStatus() != null && offer.getStatus().isTerminal()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ILLEGAL_TRANSITION_MESSAGE);
        }
    }

    /**
     * Applies {@code action} to the offer through the pure {@link OfferStatusMachine} (using the
     * acting role resolved from the security context) and assigns the resulting status. The machine
     * throws 409 {@code error.offer.illegal.transition} on any illegal triple, leaving the offer
     * unchanged (R3.8 / R10.5).
     */
    private void transitionStatus(OfferEntity offer, OfferAction action) {
        OfferStatus next = offerStatusMachine.transition(offer.getStatus(), action, resolveActorRole());
        offer.setStatus(next);
    }

    /**
     * Applies a negotiation-driven status {@code action} to {@code offer} through the shared
     * {@link OfferStatusMachine} (R3.8 / R10.5) and assigns the resulting status, without saving.
     * This is the <b>shared status-transition entry point</b> the sibling {@code NegotiationService}
     * (task 7) routes its two status-affecting mutators through — {@code openDiscountRequest} drives
     * {@link OfferAction#REQUEST_CHANGES} ({@code SENT}/{@code COUNTERED → CHANGES_REQUESTED}, R3.3)
     * and {@code managerPropose} drives {@link OfferAction#PROPOSE} ({@code CHANGES_REQUESTED →
     * COUNTERED}, R3.4) — so every offer status change stays funnelled through the single status
     * machine rather than being assigned directly. The caller persists the offer after its own
     * negotiation writes.
     *
     * @param offer  the offer whose status is transitioned (validated by the machine)
     * @param action the negotiation-driving action
     * @throws ForemenApiException 409 {@code error.offer.illegal.transition} on an illegal triple
     */
    public void transitionOfferStatus(OfferEntity offer, OfferAction action) {
        transitionStatus(offer, action);
    }

    /**
     * Advances the project from {@code expected} to {@code target} when it is currently in
     * {@code expected}. The offer status machine is the authoritative gate on whether the transition
     * is legal; the project move mirrors it (R3.2 send, R3.5 approve). If the project is already in
     * {@code target} (idempotent replay) or another status, it is left as-is rather than forced.
     */
    private void advanceProjectStatus(ProjectEntity project, ProjectStatus expected, ProjectStatus target) {
        if (project != null && project.getStatus() == expected) {
            project.setStatus(target);
        }
    }

    /**
     * Recomputes and stores the offer totals from the live-referenced estimate prices and the
     * offer's currently-applied discounts (R1.3 / R2.6). This is the <b>shared recompute helper</b>
     * every write path that changes the priced proposal (package/finishing selection here,
     * discount add/edit/remove in {@code OfferDiscountService}, R2.6) must call, so the totals are
     * always derived consistently from the live estimate prices, the effective (surviving) discounts
     * resolved by {@link DiscountResolver}, and the project VAT rate.
     *
     * <p>The effective discounts are resolved over the offer's own {@code discounts} collection and
     * the estimate's lines (the same {@code (line → category, netBase)} shape the resolver expects),
     * then folded into totals by {@link OfferTotalsCalculator}. An offer with no discounts recomputes
     * to the plain estimate net (used by {@link #prepareOffer(Long)} on a fresh offer).
     *
     * @param offer the offer whose {@code totalNet}/{@code totalVat}/{@code totalGross} are refreshed;
     *              its {@code estimate} live reference supplies the prices
     */
    @Transactional
    public void recomputeTotals(OfferEntity offer) {
        EstimateEntity estimate = offer.getEstimate();
        List<EstimateLineEntity> lines = estimate.getLines() != null ? estimate.getLines() : List.of();

        Map<Long, EffectiveDiscount> effective = discountResolver.resolveEffective(
                toDiscountInputs(offer.getDiscounts()),
                toLineInputs(lines));

        OfferTotals totals = offerTotalsCalculator.compute(
                estimateClientPrices(estimate),
                effective,
                vatRateOf(estimate));
        offer.setTotalNet(totals.totalNet());
        offer.setTotalVat(totals.totalVat());
        offer.setTotalGross(totals.totalGross());
    }

    /** Maps the offer's applied {@link OfferDiscountEntity} rows to the resolver's pure inputs. */
    private static List<DiscountInput> toDiscountInputs(List<OfferDiscountEntity> discounts) {
        List<DiscountInput> inputs = new ArrayList<>();
        if (discounts != null) {
            for (OfferDiscountEntity d : discounts) {
                if (d != null) {
                    inputs.add(new DiscountInput(d.getScope(), d.getTargetId(), d.getKind(), d.getValue()));
                }
            }
        }
        return inputs;
    }

    /**
     * Maps the estimate's lines to the resolver's per-line inputs: line id, its work-category (the
     * work-type group id a {@code CATEGORY} discount targets), and its client-facing net base.
     */
    private static List<EstimateLineInput> toLineInputs(List<EstimateLineEntity> lines) {
        List<EstimateLineInput> inputs = new ArrayList<>();
        for (EstimateLineEntity line : lines) {
            if (line == null || line.getId() == null) {
                continue;
            }
            inputs.add(new EstimateLineInput(line.getId(), categoryIdOf(line), line.getValueNet()));
        }
        return inputs;
    }

    /** The work-category (work-type group) id of a line, or {@code null} when unresolved. */
    private static Long categoryIdOf(EstimateLineEntity line) {
        if (line.getWorkItem() == null || line.getWorkItem().getWorkCategory() == null) {
            return null;
        }
        return line.getWorkItem().getWorkCategory().getId();
    }

    /**
     * The estimate's client-facing final net keyed by estimate-line id — the live-referenced prices
     * the offer totals are computed over (R1.3 / R19.4). The offer stores no copy of these; they are
     * read from the estimate's lines on every recompute.
     */
    private Map<Long, BigDecimal> estimateClientPrices(EstimateEntity estimate) {
        Map<Long, BigDecimal> prices = new HashMap<>();
        if (estimate.getLines() != null) {
            for (EstimateLineEntity line : estimate.getLines()) {
                if (line != null && line.getId() != null) {
                    prices.put(line.getId(), line.getValueNet());
                }
            }
        }
        return prices;
    }

    /** The project VAT rate as a percentage, or zero when the estimate has no rate. */
    private BigDecimal vatRateOf(EstimateEntity estimate) {
        VatRateEntity vatRate = estimate.getVatRate();
        return vatRate != null && vatRate.getRate() != null ? vatRate.getRate() : BigDecimal.ZERO;
    }

    /** Resolves the estimate's applied package code to an {@link OfferPackageEntity}, null-safe (R1.7). */
    private OfferPackageEntity resolveAppliedPackageOrNull(EstimateEntity estimate) {
        return resolvePackageOrNull(estimate.getAppliedPackageCode());
    }

    /** Resolves a package by code; returns {@code null} for a null/blank/unknown code (R1.7). */
    private OfferPackageEntity resolvePackageOrNull(String packageCode) {
        if (packageCode == null || packageCode.isBlank()) {
            return null;
        }
        return offerPackageDao.findByCode(packageCode).orElse(null);
    }

    /** Whether two package references denote the same package (by id), treating null as no package. */
    private static boolean samePackage(OfferPackageEntity a, OfferPackageEntity b) {
        Long aId = a == null ? null : a.getId();
        Long bId = b == null ? null : b.getId();
        return aId == null ? bId == null : aId.equals(bId);
    }

    /**
     * Resolves the acting caller's role code for the {@link OfferStatusMachine}: {@code "ADMIN"} when
     * the authentication carries the ADMIN authority, otherwise the role code of the user identified
     * by the numeric principal name. Returns {@code null} when no role can be resolved — the status
     * machine treats a {@code null} role as an unauthorized actor and rejects the transition.
     */
    private String resolveActorRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if ("ROLE_ADMIN".equals(a) || "ADMIN".equals(a)) {
                return "ADMIN";
            }
        }
        Long userId = parseUserId(auth.getName());
        if (userId == null) {
            return null;
        }
        UserEntity user = userDao.findById(userId).orElse(null);
        if (user == null) {
            return null;
        }
        RoleEntity role = user.getRole();
        return role != null ? role.getCode() : null;
    }

    private static Long parseUserId(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(name.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
