package com.foremen.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.DeliveryCategoryDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.PurchaseRequestDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.PurchaseRequestServiceExtendedModel;
import com.foremen.service.model.PurchaseRequestServiceModel;
import com.foremen.service.model.mapper.PurchaseRequestServiceMapper;
import com.foremen.service.procurement.HideDeliveredFilter;
import com.foremen.service.procurement.PlannedDeliveryDateSynchronizer;
import com.foremen.service.procurement.PurchaseRequestAssignedEvent;
import com.foremen.service.procurement.PurchaseRequestOverdue;
import com.foremen.service.procurement.access.ProcurementProjectAccess;

import jakarta.persistence.EntityManager;

/**
 * CRUD + attach/detach service for purchase requests (FOR-11-00 R11, R13.4).
 *
 * <p>Scoping: {@link #getProjectIdPath()} is {@code "project.id"}; {@link #allowedProjectIds(Long)}
 * delegates to {@link ProcurementProjectAccess} (BUYER sees all projects).
 *
 * <p>Rules: {@code assignedBuyer} must be an active user with role {@code BUYER} (400
 * {@code error.purchaseRequest.buyer.invalid}); a request is deletable only when it has no positions;
 * attach requires same project, not in another request, not {@code CANCELLED}; detach is allowed only
 * at stage {@code PLANNED}/{@code PROPOSED}/{@code ACCEPTED}. A {@link PurchaseRequestAssignedEvent}
 * is published when the buyer is set or changed to a non-null user; moving the expected delivery date
 * to today or later clears {@code overdueNotifiedAt} so the overdue notification can fire again.
 *
 * <p>The positions' {@code plannedDeliveryDate} is kept in sync by {@link PlannedDeliveryDateSynchronizer}
 * on attach, detach and expected-date change (R11.11).
 */
@Service
public class PurchaseRequestService implements ProjectScopedService<PurchaseRequestServiceModel,
        PurchaseRequestServiceExtendedModel, PurchaseRequestEntity, Long> {

    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";
    static final String NOT_FOUND_MESSAGE = "error.purchaseRequest.notFound";
    static final String BUYER_INVALID_MESSAGE = "error.purchaseRequest.buyer.invalid";
    static final String PROJECT_MISMATCH_MESSAGE = "error.purchaseRequest.position.projectMismatch";
    static final String ALREADY_ASSIGNED_MESSAGE = "error.purchaseRequest.position.alreadyAssigned";
    static final String DETACH_NOT_ALLOWED_MESSAGE = "error.purchaseRequest.position.detachNotAllowed";
    static final String DELETE_NOT_EMPTY_MESSAGE = "error.purchaseRequest.delete.notEmpty";
    static final String CANCELLED_POSITION_MESSAGE = "error.purchasePosition.transition.notAllowed";

    private static final Set<PositionStatus> DETACHABLE =
            EnumSet.of(PositionStatus.PLANNED, PositionStatus.PROPOSED, PositionStatus.ACCEPTED);

    private final PurchaseRequestDao dao;
    private final PurchaseRequestServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final ProcurementProjectAccess projectAccess;
    private final ProjectDao projectDao;
    private final UserDao userDao;
    private final DeliveryCategoryDao deliveryCategoryDao;
    private final PurchasePositionDao positionDao;
    private final ApplicationEventPublisher eventPublisher;
    private final PurchaseRequestOverdue overdue;
    private final PlannedDeliveryDateSynchronizer plannedDeliveryDateSynchronizer;

    public PurchaseRequestService(PurchaseRequestDao dao,
                                  PurchaseRequestServiceMapper mapper,
                                  AuditLogDao auditLogDao,
                                  EntityManager entityManager,
                                  ProcurementProjectAccess projectAccess,
                                  ProjectDao projectDao,
                                  UserDao userDao,
                                  DeliveryCategoryDao deliveryCategoryDao,
                                  PurchasePositionDao positionDao,
                                  ApplicationEventPublisher eventPublisher,
                                  Clock clock,
                                  PlannedDeliveryDateSynchronizer plannedDeliveryDateSynchronizer) {
        this.dao = dao;
        this.mapper = mapper;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.projectAccess = projectAccess;
        this.projectDao = projectDao;
        this.userDao = userDao;
        this.deliveryCategoryDao = deliveryCategoryDao;
        this.positionDao = positionDao;
        this.eventPublisher = eventPublisher;
        this.overdue = new PurchaseRequestOverdue(clock);
        this.plannedDeliveryDateSynchronizer = plannedDeliveryDateSynchronizer;
    }

    /** Composes the R22 {@code hideDelivered} filter (derived status COMPLETED) with user and scope specs. */
    @Override
    public Specification<PurchaseRequestEntity> buildFinalSpecification(String rawQuery) {
        Specification<PurchaseRequestEntity> base = ProjectScopedService.super.buildFinalSpecification(rawQuery);
        return HideDeliveredFilter.requested()
                ? Specification.where(base).and(HideDeliveredFilter.requests())
                : base;
    }

    // --- CRUD plumbing ---

    @Override
    public PurchaseRequestDao getDao() {
        return dao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<PurchaseRequestEntity, PurchaseRequestServiceModel,
            PurchaseRequestServiceExtendedModel> getMapper() {
        return mapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<PurchaseRequestEntity> getDaoModelClass() {
        return PurchaseRequestEntity.class;
    }

    // --- Project scoping ---

    @Override
    public String getProjectIdPath() {
        return "project.id";
    }

    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccess.allowedProjectIds(userId);
    }

    // --- Validation ---

    @Override
    public void validateCreate(PurchaseRequestServiceExtendedModel model) {
        Long projectId = model.getProjectId();
        if (projectId == null || !projectDao.existsById(projectId)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, projectId);
        }
        assertCallerMayUseProject(projectId);
        validateDeliveryCategory(model.getDeliveryCategoryId());
        validateBuyer(model.getAssignedBuyerId());
    }

    @Override
    public void validateUpdate(PurchaseRequestEntity existing, PurchaseRequestServiceExtendedModel model) {
        validateDeliveryCategory(model.getDeliveryCategoryId());
        validateBuyer(model.getAssignedBuyerId());
    }

    private void validateDeliveryCategory(Long categoryId) {
        if (categoryId != null && !deliveryCategoryDao.existsById(categoryId)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, categoryId);
        }
    }

    /** The buyer must exist, be active and carry the {@code BUYER} role. */
    private void validateBuyer(Long buyerId) {
        if (buyerId == null) {
            return;
        }
        boolean valid = userDao.findById(buyerId).filter(UserEntity::isActive).isPresent()
                && userDao.findRoleCodeById(buyerId)
                        .filter(ProcurementProjectAccess.BUYER_ROLE_CODE::equals).isPresent();
        if (!valid) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, BUYER_INVALID_MESSAGE);
        }
    }

    /** Non-ADMIN callers may create only inside a project they can access; otherwise 404 like a missing one. */
    private void assertCallerMayUseProject(Long projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, projectId);
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(ga.getAuthority()) || "ADMIN".equals(ga.getAuthority())) {
                return;
            }
        }
        Long userId;
        try {
            userId = Long.parseLong(auth.getName().trim());
        } catch (RuntimeException e) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, projectId);
        }
        Set<Long> allowed = allowedProjectIds(userId);
        if (allowed == null || !allowed.contains(projectId)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, projectId);
        }
    }

    // --- Events and overdue reset ---

    @Override
    public void afterCreate(PurchaseRequestEntity entity) {
        plannedDeliveryDateSynchronizer.onRequestDateChanged(entity);
        if (entity.getAssignedBuyer() != null) {
            eventPublisher.publishEvent(
                    new PurchaseRequestAssignedEvent(entity.getId(), entity.getAssignedBuyer().getId()));
        }
    }

    @Override
    @Transactional
    public PurchaseRequestServiceExtendedModel update(Long id, PurchaseRequestServiceExtendedModel model) {
        PurchaseRequestEntity existing = dao.findById(id).orElse(null);
        LocalDate oldDate = existing == null ? null : existing.getExpectedDeliveryDate();
        Long oldBuyerId = existing == null || existing.getAssignedBuyer() == null
                ? null : existing.getAssignedBuyer().getId();

        PurchaseRequestServiceExtendedModel result = ProjectScopedService.super.update(id, model);

        if (existing != null) {
            LocalDate newDate = existing.getExpectedDeliveryDate();
            if (!Objects.equals(oldDate, newDate)) {
                plannedDeliveryDateSynchronizer.onRequestDateChanged(existing);
            }
            if (!Objects.equals(oldDate, newDate) && newDate != null && !newDate.isBefore(overdue.today())) {
                existing.setOverdueNotifiedAt(null);
            }
            Long newBuyerId = existing.getAssignedBuyer() == null ? null : existing.getAssignedBuyer().getId();
            if (newBuyerId != null && !Objects.equals(oldBuyerId, newBuyerId)) {
                eventPublisher.publishEvent(new PurchaseRequestAssignedEvent(id, newBuyerId));
            }
        }
        return result;
    }

    /** Clearing {@code expectedDeliveryDate} must clear the positions' planned dates too (R11.11). */
    @Override
    @Transactional
    public void setPropertiesToNull(Long id, Set<String> propertyNames) {
        ProjectScopedService.super.setPropertiesToNull(id, propertyNames);
        if (propertyNames.contains("expectedDeliveryDate")) {
            dao.findById(id).ifPresent(r -> {
                r.setExpectedDeliveryDate(null);
                plannedDeliveryDateSynchronizer.onRequestDateChanged(r);
            });
        }
    }

    // --- Delete: only an empty request ---

    @Override
    @Transactional
    public void deleteById(Long id) {
        assertProjectAccess(id);
        requireEmpty(id);
        ProjectScopedService.super.deleteById(id);
    }

    @Override
    @Transactional
    public void deleteAll(List<Long> ids) {
        ids.forEach(this::assertProjectAccess);
        ids.forEach(this::requireEmpty);
        ProjectScopedService.super.deleteAll(ids);
    }

    private void requireEmpty(Long id) {
        if (!positionDao.findByPurchaseRequestId(id).isEmpty()) {
            throw new ForemenApiException(HttpStatus.CONFLICT, DELETE_NOT_EMPTY_MESSAGE);
        }
    }

    // --- Attach / detach ---

    /**
     * Attaches positions to the request. Each must belong to the request's project, must not be in
     * another request and must not be {@code CANCELLED}; a position already in this request is skipped.
     */
    @Transactional
    public PurchaseRequestServiceExtendedModel attachPositions(Long requestId, List<Long> positionIds) {
        assertProjectAccess(requestId);
        PurchaseRequestEntity request = requireRequest(requestId);
        Long projectId = request.getProject().getId();

        for (Long positionId : new LinkedHashSet<>(positionIds)) {
            PurchasePositionEntity position = positionDao.findById(positionId)
                    .orElseThrow(() -> new ForemenApiException(
                            HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, positionId));
            if (!Objects.equals(position.getProject().getId(), projectId)) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, PROJECT_MISMATCH_MESSAGE);
            }
            PurchaseRequestEntity current = position.getPurchaseRequest();
            if (current != null) {
                if (Objects.equals(current.getId(), requestId)) {
                    continue;
                }
                throw new ForemenApiException(HttpStatus.CONFLICT, ALREADY_ASSIGNED_MESSAGE);
            }
            if (position.getStatus() == PositionStatus.CANCELLED) {
                throw new ForemenApiException(HttpStatus.CONFLICT, CANCELLED_POSITION_MESSAGE);
            }
            position.setPurchaseRequest(request);
            plannedDeliveryDateSynchronizer.onAttach(position, request);
            positionDao.save(position);
        }
        entityManager.flush();
        return mapper.toServiceExtendedModel(request);
    }

    /** Detaches a position while its stage is PLANNED, PROPOSED or ACCEPTED. */
    @Transactional
    public PurchaseRequestServiceExtendedModel detachPosition(Long requestId, Long positionId) {
        assertProjectAccess(requestId);
        PurchaseRequestEntity request = requireRequest(requestId);
        PurchasePositionEntity position = positionDao.findById(positionId)
                .filter(p -> p.getPurchaseRequest() != null && Objects.equals(p.getPurchaseRequest().getId(), requestId))
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, positionId));
        if (!DETACHABLE.contains(position.getStatus())) {
            throw new ForemenApiException(HttpStatus.CONFLICT, DETACH_NOT_ALLOWED_MESSAGE);
        }
        position.setPurchaseRequest(null);
        plannedDeliveryDateSynchronizer.onDetach(position);
        positionDao.save(position);
        entityManager.flush();
        return mapper.toServiceExtendedModel(request);
    }

    private PurchaseRequestEntity requireRequest(Long id) {
        return dao.findById(id).orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE));
    }
}
