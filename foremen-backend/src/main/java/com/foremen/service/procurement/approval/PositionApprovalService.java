package com.foremen.service.procurement.approval;

import java.time.Clock;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.PositionApprovalOptionDao;
import com.foremen.dao.PositionApprovalRoundDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.VariantReason;
import com.foremen.dao.model.PositionApprovalOptionEntity;
import com.foremen.dao.model.PositionApprovalRoundEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.ProjectScopedService;
import com.foremen.service.PurchasePositionService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.PositionApprovalRoundServiceModel;
import com.foremen.service.model.mapper.PositionApprovalRoundServiceMapper;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.position.PositionLifecycleService;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;
import com.foremen.service.procurement.position.PositionVariantService;
import com.foremen.service.procurement.position.ReplaceCommand;

import jakarta.persistence.EntityManager;

/**
 * FOR-11-02: orchestrator of the client approval workflow. This part covers sending positions for a
 * decision ({@link #send}) and a package choice ({@link #sendChoice}); decisions, analogs and withdrawals
 * are added by the following tasks.
 *
 * <p>Rounds orchestrate, positions keep the truth: the position stage changes only through
 * {@link PositionLifecycleService} with {@link Actor#SYSTEM}. Project scope is
 * {@code position.project.id}, resolved through {@link ProcurementProjectAccess}.
 */
@Service
public class PositionApprovalService implements ProjectScopedService<PositionApprovalRoundServiceModel,
        PositionApprovalRoundServiceModel, PositionApprovalRoundEntity, Long> {

    public static final String ERROR_SEND_NOT_ELIGIBLE = "error.positionApproval.send.notEligible";
    public static final String ERROR_SEND_PRICE_MISSING = "error.positionApproval.send.priceMissing";
    public static final String ERROR_OPTIONS_INVALID = "error.positionApproval.choice.optionsInvalid";
    public static final String ERROR_MATERIAL_PROJECT_MISMATCH = "error.positionApproval.material.projectMismatch";
    public static final String ERROR_ROUND_NOT_FOUND = "error.positionApproval.round.notFound";
    public static final String ERROR_ROUND_NOT_OPEN = "error.positionApproval.round.notOpen";
    public static final String ERROR_STAGE_NOT_ALLOWED = "error.positionApproval.stage.notAllowed";
    public static final String ERROR_STALE = "error.positionApproval.stale";
    public static final String ERROR_PRICE_CHANGED = "error.positionApproval.priceChanged";
    public static final String ERROR_OPTION_INVALID = "error.positionApproval.option.invalid";
    public static final String ERROR_OPTION_PRICE_NOT_ALLOWED = "error.positionApproval.option.priceNotAllowed";
    public static final String ERROR_COMMENT_REQUIRED = "error.positionApproval.decision.commentRequired";
    public static final String ERROR_LINK_INVALID = "error.positionApproval.suggestion.linkInvalid";
    public static final String ERROR_CLIENT_OWN_NOT_ALLOWED = "error.positionApproval.clientOwn.notAllowed";
    public static final String ERROR_SAME_AS_CURRENT = "error.positionApproval.option.sameAsCurrent";
    public static final String CLIENT_ROLE_CODE = "CLIENT";
    static final int MAX_TEXT = 1000;
    static final String ENTITY_NOT_FOUND = "error.entity.not.found";
    static final int MIN_CHOICE_OPTIONS = 2;
    static final int MAX_CHOICE_OPTIONS = 5;

    /** Body of {@code POST /staff/send}. */
    public record SendCommand(List<Long> positionIds, LocalDate decisionDeadline, String managerNote) {
    }

    /** Body of {@code POST /staff/send-choice}. */
    public record SendChoiceCommand(Long positionId, List<Long> materialIds, LocalDate decisionDeadline,
                                    String managerNote) {
    }

    /** Body of {@code accept} (client) and of an ACCEPT on behalf. */
    public record AcceptCommand(Long optionId, BigDecimal expectedUnitSalePrice, String comment) {
    }

    /** Body of {@code POST /staff/positions/{id}/propose-analog}. */
    public record AnalogCommand(Long materialId, BigDecimal unitSalePrice, BigDecimal internalUnitPurchasePrice,
                                LocalDate decisionDeadline, String managerNote, Long sourceSuggestionId) {
    }

    /** Body of {@code suggest}. */
    public record SuggestCommand(String link, String comment) {
    }

    /**
     * Who records a decision. The client endpoints and the on-behalf endpoints share one code path (R12.2);
     * only this record differs. A client decider is checked for project membership, an on-behalf decider
     * (MANAGER/ADMIN, checked by the controller) is not.
     */
    public record Decider(Long userId, DecidedSide side, boolean onBehalf) {
        public static Decider client(Long userId) {
            return new Decider(userId, DecidedSide.CLIENT, false);
        }

        public static Decider staffOnBehalf(Long userId) {
            return new Decider(userId, DecidedSide.STAFF, true);
        }
    }

    private final PositionApprovalRoundDao roundDao;
    private final PositionApprovalOptionDao optionDao;
    private final PurchasePositionDao positionDao;
    private final PurchasePositionService positionService;
    private final PositionLifecycleService lifecycleService;
    private final FinishingMaterialDao materialDao;
    private final PositionVariantService variantService;
    private final ProjectMemberDao projectMemberDao;
    private final PositionApprovalRoundServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final ProcurementProjectAccess projectAccess;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PositionApprovalService(PositionApprovalRoundDao roundDao,
                                   PositionApprovalOptionDao optionDao,
                                   PurchasePositionDao positionDao,
                                   PurchasePositionService positionService,
                                   PositionLifecycleService lifecycleService,
                                   FinishingMaterialDao materialDao,
                                   PositionVariantService variantService,
                                   ProjectMemberDao projectMemberDao,
                                   PositionApprovalRoundServiceMapper mapper,
                                   AuditLogDao auditLogDao,
                                   EntityManager entityManager,
                                   ProcurementProjectAccess projectAccess,
                                   ApplicationEventPublisher events,
                                   Clock clock) {
        this.roundDao = roundDao;
        this.optionDao = optionDao;
        this.positionDao = positionDao;
        this.positionService = positionService;
        this.lifecycleService = lifecycleService;
        this.materialDao = materialDao;
        this.variantService = variantService;
        this.projectMemberDao = projectMemberDao;
        this.mapper = mapper;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.projectAccess = projectAccess;
        this.events = events;
        this.clock = clock;
    }

    // --- CRUD plumbing / project scope (R21.2) ---

    @Override
    public PositionApprovalRoundDao getDao() {
        return roundDao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<PositionApprovalRoundEntity, PositionApprovalRoundServiceModel,
            PositionApprovalRoundServiceModel> getMapper() {
        return mapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<PositionApprovalRoundEntity> getDaoModelClass() {
        return PositionApprovalRoundEntity.class;
    }

    @Override
    public String getProjectIdPath() {
        return "position.project.id";
    }

    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccess.allowedProjectIds(userId);
    }

    /** Flat, cycle-free audit snapshot; carries no price and no admin-only field (R2.7). */
    @Override
    public String serializeEntity(PositionApprovalRoundEntity e) {
        if (e == null) {
            return null;
        }
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("id", e.getId());
        snap.put("positionId", e.getPosition() == null ? null : e.getPosition().getId());
        snap.put("roundNo", e.getRoundNo());
        snap.put("kind", e.getKind());
        snap.put("status", e.getStatus());
        snap.put("decisionDeadline", e.getDecisionDeadline());
        snap.put("previousStatus", e.getPreviousStatus());
        snap.put("decidedSide", e.getDecidedSide());
        snap.put("onBehalf", e.isOnBehalf());
        try {
            return AUDIT_OBJECT_MAPPER.writeValueAsString(snap);
        } catch (Exception ex) {
            return "{\"error\":\"serialization_failed\",\"class\":\"PositionApprovalRoundEntity\"}";
        }
    }

    // --- Send (R6.1-6.6) ---

    /**
     * All-or-nothing: every position is validated first, then one {@code PROPOSAL} round per position is
     * created and the position moves {@code PLANNED -> PROPOSED} (SYSTEM). Returns the new round ids in
     * the request order.
     */
    @Transactional
    public List<Long> send(SendCommand cmd) {
        List<Long> ids = distinctIds(cmd == null ? null : cmd.positionIds());
        if (ids.isEmpty()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.invalid.field.name", "positionIds");
        }
        ApprovalDeadlinePolicy.validate(cmd.decisionDeadline(), ApprovalDeadlinePolicy.today(clock));

        List<PurchasePositionEntity> positions = loadScoped(ids);
                for (PurchasePositionEntity p : positions) {
            if (!isEligible(p, ApprovalOperation.SEND) || hasOpenRound(p.getId())) {
                throw notEligible(p.getId());
            }
            if (p.getSalePrice() == null || p.getSalePrice().signum() <= 0 || p.getSaleVatRate() == null) {
                throw new ForemenApiException(HttpStatus.CONFLICT, ERROR_SEND_PRICE_MISSING);
            }
        }

        List<Long> roundIds = new ArrayList<>();
        for (PurchasePositionEntity p : positions) {
            PositionApprovalRoundEntity round = newRound(p, ApprovalRoundKind.PROPOSAL, cmd.decisionDeadline(),
                    cmd.managerNote());
            saveRound(round, p.getId());

            PositionApprovalOptionEntity option = new PositionApprovalOptionEntity();
            option.setRound(round);
            option.setMaterial(p.getPlannedMaterial());
            option.setSortOrder(0);
            option.setUnitSalePrice(p.getSalePrice());
            option.setInternalUnitPurchasePrice(p.getPlannedPurchasePrice());
            optionDao.save(option);

            mirrorDeadlineAndPropose(p, cmd.decisionDeadline());
            roundIds.add(round.getId());
        }
        publishSent(positions);
        return roundIds;
    }

    // --- Send choice (R6.7) ---

    /**
     * A {@code PACKAGE_CHOICE} round for a {@code PACKAGE} position in {@code PLANNED}: 2..5 distinct,
     * price-less options. The position status is unchanged.
     */
    @Transactional
    public Long sendChoice(SendChoiceCommand cmd) {
        if (cmd == null || cmd.positionId() == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.invalid.field.name", "positionId");
        }
        List<Long> materialIds = cmd.materialIds() == null ? List.of() : cmd.materialIds();
        Set<Long> distinct = new LinkedHashSet<>(materialIds);
        if (materialIds.size() < MIN_CHOICE_OPTIONS || materialIds.size() > MAX_CHOICE_OPTIONS
                || distinct.size() != materialIds.size() || distinct.contains(null)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, ERROR_OPTIONS_INVALID);
        }
        ApprovalDeadlinePolicy.validate(cmd.decisionDeadline(), ApprovalDeadlinePolicy.today(clock));

        PurchasePositionEntity position = loadScoped(List.of(cmd.positionId())).get(0);
        if (!isEligible(position, ApprovalOperation.SEND_CHOICE)
                || hasOpenRound(position.getId())) {
            throw notEligible(position.getId());
        }
        Long projectId = position.getProject().getId();
        List<FinishingMaterialEntity> materials = new ArrayList<>();
        for (Long materialId : distinct) {
            FinishingMaterialEntity material = materialDao.findById(materialId)
                    .orElseThrow(() -> new ForemenApiException(
                            HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, "materialId", materialId));
            if (material.getProject() != null && !Objects.equals(material.getProject().getId(), projectId)) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, ERROR_MATERIAL_PROJECT_MISMATCH);
            }
            materials.add(material);
        }

        PositionApprovalRoundEntity round = newRound(position, ApprovalRoundKind.PACKAGE_CHOICE,
                cmd.decisionDeadline(), cmd.managerNote());
        saveRound(round, position.getId());
        int order = 0;
        for (FinishingMaterialEntity material : materials) {
            PositionApprovalOptionEntity option = new PositionApprovalOptionEntity();
            option.setRound(round);
            option.setMaterial(material);
            option.setSortOrder(order++);
            optionDao.save(option); // price-less: unitSalePrice and internal price stay null
        }
        if (!Objects.equals(position.getDecisionDeadline(), cmd.decisionDeadline())) {
            String before = positionService.serializeEntity(position);
            position.setDecisionDeadline(cmd.decisionDeadline());
            PurchasePositionEntity saved = positionDao.save(position);
            positionService.saveAuditWithSnapshot(before, saved, "UPDATE");
        }
        publishSent(List.of(position));
        return round.getId();
    }

    // --- Accept (R7, R12) ---

    /** Client (or on-behalf) accept of an open round; see design section 3, "Accept algorithm". */
    @Transactional
    public void accept(Long roundId, AcceptCommand cmd, Decider decider) {
        AcceptCommand command = cmd == null ? new AcceptCommand(null, null, null) : cmd;
        String comment = decisionComment(command.comment(), decider);
        PositionApprovalRoundEntity round = lockOpenRound(roundId, ApprovalRoundStatus.ACCEPTED);
        PurchasePositionEntity position = round.getPosition();
        assertDecider(position, decider);
        assertStage(position, ApprovalOperation.ACCEPT);

        PositionApprovalOptionEntity option = resolveOption(round, command.optionId());
        if (round.getKind() == ApprovalRoundKind.PACKAGE_CHOICE) {
            if (command.expectedUnitSalePrice() != null) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, ERROR_OPTION_PRICE_NOT_ALLOWED);
            }
        } else if (command.expectedUnitSalePrice() == null || option.getUnitSalePrice() == null
                || option.getUnitSalePrice().compareTo(command.expectedUnitSalePrice()) != 0) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ERROR_PRICE_CHANGED);
        }
        if (round.getKind() == ApprovalRoundKind.PROPOSAL && !baseUnchanged(round, position)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ERROR_STALE);
        }
        if (round.getKind() != ApprovalRoundKind.PROPOSAL && differsFromCurrent(option, position)) {
            variantService.replace(position.getId(), new ReplaceCommand(option.getMaterial().getId(),
                    option.getUnitSalePrice(), option.getInternalUnitPurchasePrice(), VariantReason.ANALOG,
                    round.getManagerNote()), Actor.SYSTEM);
        }
        if (position.getSourcingPath() == SourcingPath.AGREED) {
            lifecycleService.transition(position.getId(), PositionStatus.ACCEPTED, Actor.SYSTEM);
        }
        closeRound(round, ApprovalRoundStatus.ACCEPTED, decider, comment);
        round.setChosenOption(option);
        saveRoundChange(round);
        clearDeadline(position);
        events.publishEvent(new PositionDecisionEvent(position.getProject().getId(), position.getId(),
                PositionDecisionEvent.Outcome.ACCEPTED, decider.onBehalf(), decider.userId()));
    }

    // --- Reject (R8, R12) ---

    /** Client (or on-behalf) reject; the comment is mandatory. */
    @Transactional
    public void reject(Long roundId, String comment, Decider decider) {
        String text = requiredComment(comment);
        PositionApprovalRoundEntity round = lockOpenRound(roundId, ApprovalRoundStatus.REJECTED);
        PurchasePositionEntity position = round.getPosition();
        assertDecider(position, decider);
        assertStage(position, ApprovalOperation.REJECT);

        if (position.getSourcingPath() == SourcingPath.AGREED) {
            lifecycleService.transition(position.getId(), PositionStatus.PLANNED, Actor.SYSTEM);
        }
        closeRound(round, ApprovalRoundStatus.REJECTED, decider, text);
        saveRoundChange(round);
        clearDeadline(position);
        events.publishEvent(new PositionDecisionEvent(position.getProject().getId(), position.getId(),
                PositionDecisionEvent.Outcome.REJECTED, decider.onBehalf(), decider.userId()));
    }

    // --- Suggest (R10.1, R10.2, R10.5) ---

    /** The client points to an own link; creates an open {@code CLIENT_SUGGESTION} round. */
    @Transactional
    public Long suggest(Long positionId, SuggestCommand cmd, Decider decider) {
        String link = cmd == null || cmd.link() == null ? "" : cmd.link().trim();
        if (!validLink(link)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, ERROR_LINK_INVALID);
        }
        String comment = cmd.comment() == null || cmd.comment().isBlank() ? null : cmd.comment().trim();
        if (comment != null && comment.length() > MAX_TEXT) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, ERROR_COMMENT_REQUIRED);
        }
        PurchasePositionEntity position = loadScoped(List.of(positionId)).get(0);
        assertDecider(position, decider);
        assertStage(position, ApprovalOperation.SUGGEST);

        PositionApprovalRoundEntity round = newRound(position, ApprovalRoundKind.CLIENT_SUGGESTION, null, null);
        round.setBaseUnitSalePrice(null);
        round.setSuggestionLink(link);
        round.setDecisionComment(comment);
        saveRound(round, position.getId());
        events.publishEvent(new PositionSuggestionEvent(position.getProject().getId(), position.getId()));
        return round.getId();
    }

    // --- Client own (R11, R12) ---

    /** "I buy it myself": supersedes the open round, records a DECLARED round, sends the position to PLANNED. */
    @Transactional
    public void declareClientOwn(Long positionId, String comment, Decider decider) {
        String text = decisionComment(comment, decider);
        PurchasePositionEntity position = loadScoped(List.of(positionId)).get(0);
        assertDecider(position, decider);
        if (position.getProject() == null || position.getProject().getStatus() != ProjectStatus.ACTIVE
                || ApprovalStageGuard.check(position.getSourcingPath(), position.getStatus(),
                        ApprovalOperation.CLIENT_OWN) != ApprovalStageGuard.Result.OK) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ERROR_CLIENT_OWN_NOT_ALLOWED);
        }
        roundDao.findOpenByPositionId(position.getId(), ApprovalRoundStatus.OPEN).ifPresent(open -> {
            open.setStatus(ApprovalRoundStatus.SUPERSEDED);
            saveRoundChange(open);
        });
        entityManager.flush();

        PositionApprovalRoundEntity round = newRound(position, ApprovalRoundKind.CLIENT_OWN, null, null);
        round.setStatus(ApprovalRoundStatus.DECLARED);
        round.setBaseUnitSalePrice(null);
        round.setDecidedBy(userRef(decider.userId()));
        round.setDecidedAt(LocalDateTime.now(clock));
        round.setDecidedSide(decider.side());
        round.setOnBehalf(decider.onBehalf());
        round.setDecisionComment(text);
        saveRound(round, position.getId());

        if (position.getStatus() == PositionStatus.ACCEPTED) {
            lifecycleService.transition(position.getId(), PositionStatus.PROPOSED, Actor.SYSTEM);
        }
        lifecycleService.transition(position.getId(), PositionStatus.PLANNED, Actor.SYSTEM);

        String before = positionService.serializeEntity(position);
        position.setSourcingPath(SourcingPath.CLIENT_OWN);
        position.setDecisionDeadline(null);
        PurchasePositionEntity saved = positionDao.save(position);
        positionService.saveAuditWithSnapshot(before, saved, "UPDATE");
        events.publishEvent(new PositionDecisionEvent(position.getProject().getId(), position.getId(),
                PositionDecisionEvent.Outcome.CLIENT_OWN, decider.onBehalf(), decider.userId()));
    }

    // --- Analog (R9) ---

    /**
     * Staff proposes an analog for an {@code AGREED} position in {@code PROPOSED} (the open round becomes
     * {@code SUPERSEDED}) or {@code ACCEPTED} (SYSTEM {@code ACCEPTED -> PROPOSED} first). The round, not the
     * position, carries the item and price until acceptance.
     */
    @Transactional
    public Long proposeAnalog(Long positionId, AnalogCommand cmd) {
        if (cmd == null || cmd.materialId() == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.invalid.field.name", "materialId");
        }
        if (cmd.unitSalePrice() == null || cmd.unitSalePrice().signum() <= 0) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.invalid.field.name", "unitSalePrice");
        }
        if (cmd.internalUnitPurchasePrice() != null && cmd.internalUnitPurchasePrice().signum() < 0) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.invalid.field.name",
                    "internalUnitPurchasePrice");
        }
        if (cmd.managerNote() != null && cmd.managerNote().length() > MAX_TEXT) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.invalid.field.name", "managerNote");
        }
        ApprovalDeadlinePolicy.validate(cmd.decisionDeadline(), ApprovalDeadlinePolicy.today(clock));

        PurchasePositionEntity position = loadScoped(List.of(positionId)).get(0);
        assertStage(position, ApprovalOperation.PROPOSE_ANALOG);

        FinishingMaterialEntity material = materialDao.findById(cmd.materialId())
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, "materialId", cmd.materialId()));
        if (material.getProject() != null
                && !Objects.equals(material.getProject().getId(), position.getProject().getId())) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, ERROR_MATERIAL_PROJECT_MISMATCH);
        }
        Long currentMaterial = position.getPlannedMaterial() == null ? null : position.getPlannedMaterial().getId();
        if (Objects.equals(currentMaterial, material.getId())
                && sameAmount(position.getSalePrice(), cmd.unitSalePrice())) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ERROR_SAME_AS_CURRENT);
        }

        PositionApprovalRoundEntity suggestion = null;
        if (cmd.sourceSuggestionId() != null) {
            suggestion = roundDao.findByIdForUpdate(cmd.sourceSuggestionId())
                    .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, ERROR_ROUND_NOT_FOUND));
            if (!Objects.equals(suggestion.getPosition().getId(), position.getId())
                    || suggestion.getKind() != ApprovalRoundKind.CLIENT_SUGGESTION) {
                throw new ForemenApiException(HttpStatus.NOT_FOUND, ERROR_ROUND_NOT_FOUND);
            }
            if (!ApprovalRoundStatusMachine.canMove(suggestion.getKind(), suggestion.getStatus(),
                    ApprovalRoundStatus.RESOLVED)) {
                throw new ForemenApiException(HttpStatus.CONFLICT, ERROR_ROUND_NOT_OPEN);
            }
        }

        PositionStatus previous = position.getStatus() == PositionStatus.ACCEPTED
                ? PositionStatus.ACCEPTED : PositionStatus.PLANNED;
        PositionApprovalRoundEntity open = roundDao.findOpenByPositionId(position.getId(), ApprovalRoundStatus.OPEN)
                .orElse(null);
        if (open != null) {
            if (open.getPreviousStatus() != null) {
                previous = open.getPreviousStatus();
            }
            open.setStatus(ApprovalRoundStatus.SUPERSEDED);
            saveRoundChange(open);
            entityManager.flush();
        }

        PositionApprovalRoundEntity round = newRound(position, ApprovalRoundKind.ANALOG, cmd.decisionDeadline(),
                trimToNull(cmd.managerNote()));
        round.setPreviousStatus(previous);
        round.setSourceSuggestion(suggestion);
        saveRound(round, position.getId());

        PositionApprovalOptionEntity option = new PositionApprovalOptionEntity();
        option.setRound(round);
        option.setMaterial(material);
        option.setSortOrder(0);
        option.setUnitSalePrice(cmd.unitSalePrice());
        option.setInternalUnitPurchasePrice(cmd.internalUnitPurchasePrice());
        optionDao.save(option);

        if (suggestion != null) {
            suggestion.setStatus(ApprovalRoundStatus.RESOLVED);
            suggestion.setDecidedAt(LocalDateTime.now(clock));
            suggestion.setDecidedSide(DecidedSide.STAFF);
            saveRoundChange(suggestion);
        }

        if (position.getStatus() == PositionStatus.ACCEPTED) {
            lifecycleService.transition(position.getId(), PositionStatus.PROPOSED, Actor.SYSTEM);
        }
        setDeadline(position, cmd.decisionDeadline());
        events.publishEvent(new PositionAnalogProposedEvent(position.getProject().getId(), position.getId()));
        return round.getId();
    }

    // --- Decline a suggestion (R10.3) ---

    /** Staff declines an open client suggestion; the reason is stored in {@code managerNote} for the client. */
    @Transactional
    public void declineSuggestion(Long roundId, String note) {
        String text = requiredComment(note);
        PositionApprovalRoundEntity round = lockOpenRound(roundId, ApprovalRoundStatus.DECLINED);
        round.setStatus(ApprovalRoundStatus.DECLINED);
        round.setManagerNote(text);
        round.setDecidedAt(LocalDateTime.now(clock));
        round.setDecidedSide(DecidedSide.STAFF);
        saveRoundChange(round);
    }

    // --- Withdraw (R13.1) ---

    /** Staff withdraws an open PROPOSAL / ANALOG / PACKAGE_CHOICE round and restores the previous stage. */
    @Transactional
    public void withdraw(Long roundId) {
        PositionApprovalRoundEntity round = lockOpenRound(roundId, ApprovalRoundStatus.WITHDRAWN);
        PurchasePositionEntity position = round.getPosition();
        if (position.getSourcingPath() == SourcingPath.AGREED) {
            if (position.getStatus() != PositionStatus.PROPOSED) {
                throw new ForemenApiException(HttpStatus.CONFLICT, ERROR_STAGE_NOT_ALLOWED);
            }
            PositionStatus target = round.getPreviousStatus() == PositionStatus.ACCEPTED
                    ? PositionStatus.ACCEPTED : PositionStatus.PLANNED;
            lifecycleService.transition(position.getId(), target, Actor.SYSTEM);
        }
        round.setStatus(ApprovalRoundStatus.WITHDRAWN);
        saveRoundChange(round);
        clearDeadline(position);
    }

    // --- Change deadline (R13.2) ---

    /** Moves (or, with {@code null}, removes) the deadline of an open round and mirrors it to the position. */
    @Transactional
    public void changeDeadline(Long roundId, LocalDate deadline) {
        ApprovalDeadlinePolicy.validate(deadline, ApprovalDeadlinePolicy.today(clock));
        PositionApprovalRoundEntity round = lockOpenRound(roundId, ApprovalRoundStatus.WITHDRAWN);
        round.setDecisionDeadline(deadline);
        saveRoundChange(round);
        setDeadline(round.getPosition(), deadline);
    }

    // --- Decision helpers ---

    private void setDeadline(PurchasePositionEntity position, LocalDate deadline) {
        if (Objects.equals(position.getDecisionDeadline(), deadline)) {
            return;
        }
        String before = positionService.serializeEntity(position);
        position.setDecisionDeadline(deadline);
        PurchasePositionEntity saved = positionDao.save(position);
        positionService.saveAuditWithSnapshot(before, saved, "UPDATE");
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Locks the round row and checks it is OPEN and may move to {@code target}. */
    private PositionApprovalRoundEntity lockOpenRound(Long roundId, ApprovalRoundStatus target) {
        if (roundId == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ERROR_ROUND_NOT_FOUND);
        }
        PositionApprovalRoundEntity round = roundDao.findByIdForUpdate(roundId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, ERROR_ROUND_NOT_FOUND));
        positionService.assertProjectAccess(round.getPosition().getId());
        if (!ApprovalRoundStatusMachine.canMove(round.getKind(), round.getStatus(), target)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ERROR_ROUND_NOT_OPEN);
        }
        return round;
    }

    private void assertStage(PurchasePositionEntity position, ApprovalOperation operation) {
        if (position.getProject() == null || position.getProject().getStatus() != ProjectStatus.ACTIVE
                || ApprovalStageGuard.check(position.getSourcingPath(), position.getStatus(), operation)
                        != ApprovalStageGuard.Result.OK) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ERROR_STAGE_NOT_ALLOWED);
        }
    }

    /** Client deciders must be ACTIVE CLIENT members of the project (404 otherwise); on-behalf skips it. */
    private void assertDecider(PurchasePositionEntity position, Decider decider) {
        if (decider == null || decider.userId() == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, position.getId());
        }
        if (decider.onBehalf()) {
            return;
        }
        boolean member = projectMemberDao.findByUserIdAndProjectId(decider.userId(), position.getProject().getId())
                .filter(m -> m.getAssignmentStatus() == AssignmentStatus.ACTIVE)
                .map(ProjectMemberEntity::getProjectRole)
                .filter(r -> CLIENT_ROLE_CODE.equals(r.getCode()))
                .isPresent();
        if (!member) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, position.getId());
        }
    }

    private PositionApprovalOptionEntity resolveOption(PositionApprovalRoundEntity round, Long optionId) {
        List<PositionApprovalOptionEntity> options =
                optionDao.findByRoundIdOrderBySortOrderAscIdAsc(round.getId());
        if (round.getKind() == ApprovalRoundKind.PACKAGE_CHOICE) {
            if (optionId == null) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, ERROR_OPTION_INVALID);
            }
            return options.stream().filter(o -> Objects.equals(o.getId(), optionId)).findFirst()
                    .orElseThrow(() -> new ForemenApiException(HttpStatus.BAD_REQUEST, ERROR_OPTION_INVALID));
        }
        if (options.isEmpty() || optionId != null && !Objects.equals(options.get(0).getId(), optionId)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, ERROR_OPTION_INVALID);
        }
        return options.get(0);
    }

    private static boolean baseUnchanged(PositionApprovalRoundEntity round, PurchasePositionEntity position) {
        Long baseMaterial = round.getBaseMaterial() == null ? null : round.getBaseMaterial().getId();
        Long current = position.getPlannedMaterial() == null ? null : position.getPlannedMaterial().getId();
        return Objects.equals(baseMaterial, current)
                && sameAmount(round.getBaseUnitSalePrice(), position.getSalePrice());
    }

    /** ANALOG compares material and price; a price-less PACKAGE_CHOICE option compares the material only. */
    private static boolean differsFromCurrent(PositionApprovalOptionEntity option, PurchasePositionEntity position) {
        Long current = position.getPlannedMaterial() == null ? null : position.getPlannedMaterial().getId();
        boolean otherMaterial = !Objects.equals(option.getMaterial().getId(), current);
        if (option.getUnitSalePrice() == null) {
            return otherMaterial;
        }
        return otherMaterial || !sameAmount(option.getUnitSalePrice(), position.getSalePrice());
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private void closeRound(PositionApprovalRoundEntity round, ApprovalRoundStatus status, Decider decider,
                            String comment) {
        round.setStatus(status);
        round.setDecidedBy(userRef(decider.userId()));
        round.setDecidedAt(LocalDateTime.now(clock));
        round.setDecidedSide(decider.side());
        round.setOnBehalf(decider.onBehalf());
        round.setDecisionComment(comment);
    }

    private void saveRoundChange(PositionApprovalRoundEntity round) {
        String before = serializeEntity(round);
        roundDao.save(round);
        saveAuditWithSnapshot(before, round, "UPDATE");
    }

    private UserEntity userRef(Long userId) {
        return entityManager.getReference(UserEntity.class, userId);
    }

    /** R7.4(d): clears the mirrored deadline with an audited position write. */
    private void clearDeadline(PurchasePositionEntity position) {
        if (position.getDecisionDeadline() == null) {
            return;
        }
        String before = positionService.serializeEntity(position);
        position.setDecisionDeadline(null);
        PurchasePositionEntity saved = positionDao.save(position);
        positionService.saveAuditWithSnapshot(before, saved, "UPDATE");
    }

    /** Comment optional for the client, mandatory for an on-behalf decision (R12.2). */
    private static String decisionComment(String comment, Decider decider) {
        if (decider != null && decider.onBehalf()) {
            return requiredComment(comment);
        }
        if (comment == null || comment.isBlank()) {
            return null;
        }
        String trimmed = comment.trim();
        if (trimmed.length() > MAX_TEXT) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, ERROR_COMMENT_REQUIRED);
        }
        return trimmed;
    }

    private static String requiredComment(String comment) {
        if (comment == null || comment.isBlank() || comment.trim().length() > MAX_TEXT) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, ERROR_COMMENT_REQUIRED);
        }
        return comment.trim();
    }

    private static boolean validLink(String link) {
        if (link.isEmpty() || link.length() > MAX_TEXT) {
            return false;
        }
        try {
            URI uri = new URI(link);
            String scheme = uri.getScheme();
            return uri.isAbsolute() && uri.getHost() != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (Exception e) {
            return false;
        }
    }

    // --- Helpers ---

    private static List<Long> distinctIds(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }

    /** Scope check per id (404 outside the caller's projects), keeps the request order. */
    private List<PurchasePositionEntity> loadScoped(List<Long> ids) {
        Map<Long, PurchasePositionEntity> found = new LinkedHashMap<>();
        for (PurchasePositionEntity p : positionDao.findAllById(ids)) {
            found.put(p.getId(), p);
        }
        List<PurchasePositionEntity> result = new ArrayList<>();
        for (Long id : ids) {
            positionService.assertProjectAccess(id);
            PurchasePositionEntity p = found.get(id);
            if (p == null) {
                throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, id);
            }
            result.add(p);
        }
        return result;
    }

    private boolean hasOpenRound(Long positionId) {
        return roundDao.findOpenByPositionId(positionId, ApprovalRoundStatus.OPEN).isPresent();
    }

    private static boolean isEligible(PurchasePositionEntity p, ApprovalOperation operation) {
        return p.getProject() != null
                && p.getProject().getStatus() == ProjectStatus.ACTIVE
                && ApprovalStageGuard.check(p.getSourcingPath(), p.getStatus(), operation)
                        == ApprovalStageGuard.Result.OK;
    }

    private static ForemenApiException notEligible(Long positionId) {
        return new ForemenApiException(HttpStatus.CONFLICT, ERROR_SEND_NOT_ELIGIBLE, positionId);
    }

    private PositionApprovalRoundEntity newRound(PurchasePositionEntity p, ApprovalRoundKind kind,
                                                 LocalDate deadline, String managerNote) {
        PositionApprovalRoundEntity round = new PositionApprovalRoundEntity();
        round.setPosition(p);
        round.setRoundNo(roundDao.maxRoundNo(p.getId()) + 1);
        round.setKind(kind);
        round.setStatus(ApprovalRoundStatus.OPEN);
        round.setDecisionDeadline(deadline);
        round.setPreviousStatus(p.getStatus());
        round.setBaseMaterial(p.getPlannedMaterial());
        round.setBaseUnitSalePrice(kind == ApprovalRoundKind.PACKAGE_CHOICE ? null : p.getSalePrice());
        round.setQuantity(p.getQuantity());
        round.setManagerNote(managerNote);
        return round;
    }

    /** Persists and audits the round; the partial unique index turns a racing send into a 409. */
    private void saveRound(PositionApprovalRoundEntity round, Long positionId) {
        try {
            roundDao.save(round);
            entityManager.flush();
        } catch (DataIntegrityViolationException e) {
            throw notEligible(positionId);
        }
        saveAuditWithSnapshot(null, round, "CREATE");
    }

    /** R6.4: mirror the deadline into the position (audited), then SYSTEM {@code PLANNED -> PROPOSED}. */
    private void mirrorDeadlineAndPropose(PurchasePositionEntity p, LocalDate deadline) {
        if (!Objects.equals(p.getDecisionDeadline(), deadline)) {
            String before = positionService.serializeEntity(p);
            p.setDecisionDeadline(deadline);
            PurchasePositionEntity saved = positionDao.save(p);
            positionService.saveAuditWithSnapshot(before, saved, "UPDATE");
        }
        lifecycleService.transition(p.getId(), PositionStatus.PROPOSED, Actor.SYSTEM);
    }

    /** One event per project of the request (a request normally spans one project). */
    private void publishSent(List<PurchasePositionEntity> positions) {
        Map<Long, List<Long>> byProject = new LinkedHashMap<>();
        for (PurchasePositionEntity p : positions) {
            byProject.computeIfAbsent(p.getProject().getId(), k -> new ArrayList<>()).add(p.getId());
        }
        byProject.forEach((projectId, ids) -> events.publishEvent(new PositionsSentForDecisionEvent(projectId, ids)));
    }
}
