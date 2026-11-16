package com.foremen.service.procurement.receipt;

import com.foremen.controller.model.GoodsReceiptDelegationDto;
import com.foremen.dao.GoodsReceiptDao;
import com.foremen.dao.GoodsReceiptDelegationDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.ShipmentDao;
import com.foremen.dao.ShipmentLineDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.DelegationStatus;
import com.foremen.dao.model.GoodsReceiptDelegationEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ShipmentEntity;
import com.foremen.dao.model.ShipmentLineEntity;
import com.foremen.dao.model.ShipmentStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FOR-11-06 (R12.1 to R12.3, R12.7): delegation of a shipment's receipt to a project WORKER.
 * <p>
 * Callers: ADMIN or an {@code ACTIVE} project MANAGER member of the shipment's project; a shipment or
 * delegation outside the caller's projects reads as 404, a project member without the MANAGER role as 403.
 * The assignee must be an {@code ACTIVE} member of that project with the project role {@code WORKER}.
 * One {@code ACTIVE} delegation per shipment: delegating again revokes the previous one (reassign).
 */
@Service
public class GoodsReceiptDelegationService {

    static final String MANAGER_ROLE = "MANAGER";
    static final String WORKER_ROLE = "WORKER";
    static final String ADMIN_ROLE = "ADMIN";

    private static final String NOT_FOUND = "error.entity.not.found";
    private static final String ACCESS_DENIED = "error.access.denied";
    private static final String ASSIGNEE_INVALID = "error.goodsReceipt.delegation.assigneeInvalid";
    private static final String SHIPMENT_CLOSED = "error.goodsReceipt.delegation.shipmentClosed";
    private static final String NOT_ACTIVE = "error.goodsReceipt.delegation.notActive";

    private final GoodsReceiptDelegationDao delegationDao;
    private final ShipmentDao shipmentDao;
    private final ShipmentLineDao lineDao;
    private final GoodsReceiptDao receiptDao;
    private final ProjectMemberDao memberDao;
    private final UserDao userDao;
    private final ProcurementProjectAccess projectAccess;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public GoodsReceiptDelegationService(GoodsReceiptDelegationDao delegationDao, ShipmentDao shipmentDao,
                                         ShipmentLineDao lineDao, GoodsReceiptDao receiptDao,
                                         ProjectMemberDao memberDao, UserDao userDao,
                                         ProcurementProjectAccess projectAccess,
                                         EntityManager entityManager, ApplicationEventPublisher events,
                                         Clock clock) {
        this.delegationDao = delegationDao;
        this.shipmentDao = shipmentDao;
        this.lineDao = lineDao;
        this.receiptDao = receiptDao;
        this.memberDao = memberDao;
        this.userDao = userDao;
        this.projectAccess = projectAccess;
        this.entityManager = entityManager;
        this.events = events;
        this.clock = clock;
    }

    /** Creates a delegation, or reassigns the shipment's ACTIVE one to {@code assigneeId}. */
    @Transactional
    public GoodsReceiptDelegationDto delegate(Long shipmentId, Long assigneeId) {
        Long actorId = requireUserId();
        ShipmentEntity shipment = shipmentId == null ? null : shipmentDao.findById(shipmentId).orElse(null);
        if (shipment == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, shipmentId);
        }
        Long projectId = shipment.getProject().getId();
        requireScope(projectId, shipmentId);
        requireManager(actorId, projectId);
        if (isClosed(shipment)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, SHIPMENT_CLOSED);
        }
        UserEntity assignee = requireWorker(assigneeId, projectId);

        OffsetDateTime now = OffsetDateTime.now(clock);
        UserEntity actor = userDao.findById(actorId).orElseThrow(
                () -> new ForemenApiException(HttpStatus.FORBIDDEN, ACCESS_DENIED));
        Long previousAssigneeId = null;
        GoodsReceiptDelegationEntity previous = delegationDao
                .findByShipmentIdAndStatus(shipmentId, DelegationStatus.ACTIVE).orElse(null);
        if (previous != null) {
            previousAssigneeId = previous.getAssignee().getId();
            previous.setStatus(DelegationStatus.REVOKED);
            previous.setRevokedAt(now);
            previous.setRevokedBy(actor);
            delegationDao.save(previous);
            entityManager.flush(); // free the partial unique index before the insert
        }
        GoodsReceiptDelegationEntity created = new GoodsReceiptDelegationEntity();
        created.setShipmentId(shipmentId);
        created.setProjectId(projectId);
        created.setAssignee(assignee);
        created.setDelegatedBy(actor);
        created.setDelegatedAt(now);
        created.setStatus(DelegationStatus.ACTIVE);
        created = delegationDao.save(created);
        entityManager.flush();
        events.publishEvent(new ReceiptDelegatedEvent(created.getId(), shipmentId, projectId, assigneeId,
                previousAssigneeId, actorId));
        return toDto(created);
    }

    /** Revokes an ACTIVE delegation (ADMIN or the project MANAGER). */
    @Transactional
    public GoodsReceiptDelegationDto revoke(Long id) {
        Long actorId = requireUserId();
        GoodsReceiptDelegationEntity d = id == null ? null : delegationDao.findById(id).orElse(null);
        if (d == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, id);
        }
        requireScope(d.getProjectId(), id);
        requireManager(actorId, d.getProjectId());
        if (d.getStatus() != DelegationStatus.ACTIVE) {
            throw new ForemenApiException(HttpStatus.CONFLICT, NOT_ACTIVE);
        }
        d.setStatus(DelegationStatus.REVOKED);
        d.setRevokedAt(OffsetDateTime.now(clock));
        d.setRevokedBy(userDao.findById(actorId).orElse(null));
        d = delegationDao.save(d);
        entityManager.flush();
        events.publishEvent(new ReceiptDelegationRevokedEvent(d.getId(), d.getShipmentId(), d.getProjectId(),
                d.getAssignee().getId(), actorId));
        return toDto(d);
    }

    /**
     * Marks the shipment's ACTIVE delegation {@code COMPLETED}. Called by {@code GoodsReceiptService} when the
     * shipment is closed by receipt; a no-op when there is none.
     */
    @Transactional
    public void completeForShipment(Long shipmentId) {
        if (shipmentId == null) {
            return;
        }
        delegationDao.findByShipmentIdAndStatus(shipmentId, DelegationStatus.ACTIVE).ifPresent(d -> {
            d.setStatus(DelegationStatus.COMPLETED);
            d.setCompletedAt(OffsetDateTime.now(clock));
            delegationDao.save(d);
        });
    }

    /** Ids of the shipments with an ACTIVE delegation to {@code userId}: the worker's allowed shipments. */
    @Transactional(readOnly = true)
    public Set<Long> activeShipmentIdsFor(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        return new HashSet<>(delegationDao.findActiveShipmentIdsByAssignee(userId));
    }

    /**
     * Paged list, project scoped. A caller whose only role is WORKER sees just his own delegations; every
     * other caller sees the delegations of the projects he can access (ADMIN: all).
     */
    @Transactional(readOnly = true)
    public Page<GoodsReceiptDelegationDto> list(Long shipmentId, Long assigneeId, DelegationStatus status,
                                                Pageable pageable) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Set<String> roles = roles(auth);
        Long userId = parseUserId(auth);
        Set<Long> allowed = null;
        Long forcedAssignee = null;
        if (!roles.contains(ADMIN_ROLE)) {
            allowed = userId == null ? Set.of() : projectAccess.allowedProjectIds(userId);
            if (roles.contains(WORKER_ROLE)) {
                forcedAssignee = userId == null ? -1L : userId;
            }
        }
        final Set<Long> allowedFinal = allowed;
        final Long forced = forcedAssignee;
        Specification<GoodsReceiptDelegationEntity> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (allowedFinal != null) {
                ps.add(allowedFinal.isEmpty() ? cb.disjunction() : root.get("projectId").in(allowedFinal));
            }
            if (forced != null) {
                ps.add(cb.equal(root.get("assignee").get("id"), forced));
            }
            if (shipmentId != null) {
                ps.add(cb.equal(root.get("shipmentId"), shipmentId));
            }
            if (assigneeId != null) {
                ps.add(cb.equal(root.get("assignee").get("id"), assigneeId));
            }
            if (status != null) {
                ps.add(cb.equal(root.get("status"), status));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("delegatedAt"), Sort.Order.desc("id")));
        return delegationDao.findAll(spec, sorted).map(this::toDto);
    }

    // --- Rules ---

    private UserEntity requireWorker(Long assigneeId, Long projectId) {
        if (assigneeId == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, ASSIGNEE_INVALID).withField("assigneeId");
        }
        ProjectMemberEntity member = memberDao.findByUserIdAndProjectId(assigneeId, projectId).orElse(null);
        if (member == null || member.getAssignmentStatus() != AssignmentStatus.ACTIVE
                || member.getProjectRole() == null || !WORKER_ROLE.equals(member.getProjectRole().getCode())) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, ASSIGNEE_INVALID).withField("assigneeId");
        }
        return member.getUser();
    }

    /** CANCELLED, or every line already resolved by receipt events (the shipment is closed by receipt). */
    private boolean isClosed(ShipmentEntity shipment) {
        if (shipment.getStatus() == ShipmentStatus.CANCELLED) {
            return true;
        }
        Long shipmentId = shipment.getId();
        Map<Long, BigDecimal> perPosition = new LinkedHashMap<>();
        for (ShipmentLineEntity l : lineDao.findByShipmentId(shipmentId)) {
            perPosition.merge(l.getPosition().getId(), l.getQuantity(), BigDecimal::add);
        }
        if (perPosition.isEmpty()) {
            return false;
        }
        Map<Long, BigDecimal> resolved = new LinkedHashMap<>();
        for (Object[] row : receiptDao.sumsByShipmentAndPosition(List.of(shipmentId))) {
            resolved.merge(((Number) row[1]).longValue(), (BigDecimal) row[3], BigDecimal::add);
        }
        for (Map.Entry<Long, BigDecimal> line : perPosition.entrySet()) {
            BigDecimal done = resolved.getOrDefault(line.getKey(), BigDecimal.ZERO);
            if (!GoodsReceiptRules.lineResolved(line.getValue(), done, BigDecimal.ZERO, BigDecimal.ZERO)) {
                return false;
            }
        }
        return true;
    }

    /** 404 when the project is outside the caller's scope (ADMIN unrestricted). */
    private void requireScope(Long projectId, Object entityId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (roles(auth).contains(ADMIN_ROLE)) {
            return;
        }
        Long userId = parseUserId(auth);
        Set<Long> allowed = userId == null ? Set.of() : projectAccess.allowedProjectIds(userId);
        if (!allowed.contains(projectId)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, entityId);
        }
    }

    /** ADMIN, or an ACTIVE MANAGER member of the project; anyone else is 403. */
    private void requireManager(Long userId, Long projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (roles(auth).contains(ADMIN_ROLE)) {
            return;
        }
        ProjectMemberEntity m = memberDao.findByUserIdAndProjectId(userId, projectId).orElse(null);
        boolean manager = m != null && m.getAssignmentStatus() == AssignmentStatus.ACTIVE
                && m.getProjectRole() != null && MANAGER_ROLE.equals(m.getProjectRole().getCode());
        if (!manager) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, ACCESS_DENIED);
        }
    }

    // --- Caller and mapping ---

    private GoodsReceiptDelegationDto toDto(GoodsReceiptDelegationEntity d) {
        UserEntity assignee = d.getAssignee();
        UserEntity by = d.getDelegatedBy();
        return new GoodsReceiptDelegationDto(d.getId(), d.getShipmentId(), d.getProjectId(),
                assignee == null ? null : assignee.getId(), assignee == null ? null : assignee.getName(),
                by == null ? null : by.getId(), by == null ? null : by.getName(),
                d.getDelegatedAt(), d.getStatus(), d.getRevokedAt(), d.getCompletedAt());
    }

    private static Long requireUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = auth == null || !auth.isAuthenticated() ? null : parseUserId(auth);
        if (userId == null) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, ACCESS_DENIED);
        }
        return userId;
    }

    private static Long parseUserId(Authentication auth) {
        if (auth == null) {
            return null;
        }
        try {
            return Long.parseLong(auth.getName().trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Set<String> roles(Authentication auth) {
        Set<String> roles = new HashSet<>();
        if (auth == null || !auth.isAuthenticated()) {
            return roles;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            roles.add(a != null && a.startsWith("ROLE_") ? a.substring(5) : a);
        }
        return roles;
    }
}
