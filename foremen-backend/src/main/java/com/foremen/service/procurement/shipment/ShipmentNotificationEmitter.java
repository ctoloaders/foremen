package com.foremen.service.procurement.shipment;

import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.service.offer.NotificationService;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-11-04 (Requirement 15) - best-effort consumer turning shipment events into in-app notifications
 * through {@link NotificationService#create} (emit only). Mirrors {@code ProformaOrderNotificationEmitter}:
 * AFTER_COMMIT with {@code fallbackExecution = true}, recipients de-duplicated, the actor removed, every
 * resolution and emission wrapped in try/catch + {@code log.error}.
 *
 * <p>Shipment types go to ACTIVE MANAGER and FOREMAN members: managers first (manager link), then foremen
 * who were not already notified (foreman link). Order-by goes to ACTIVE MANAGER members only.
 */
@Slf4j
@Component
public class ShipmentNotificationEmitter {

    static final String TYPE_SHIPPED = "notification.shipment.shipped";
    static final String TYPE_DELAYED = "notification.shipment.delayed";
    static final String TYPE_DOWNTIME_RISK = "notification.shipment.downtimeRisk";
    static final String TYPE_ORDER_BY = "notification.position.orderBy";
    static final String MANAGER_ROLE = "MANAGER";
    static final String FOREMAN_ROLE = "FOREMAN";
    /** R15.4: minimum ETA move (calendar days) that is reported as a delay. */
    static final long DELAY_THRESHOLD_DAYS = 1;

    private final NotificationService notificationService;
    private final ProjectMemberDao projectMemberDao;

    public ShipmentNotificationEmitter(NotificationService notificationService, ProjectMemberDao projectMemberDao) {
        this.notificationService = notificationService;
        this.projectMemberDao = projectMemberDao;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onShipmentCreated(ShipmentCreatedEvent event) {
        if (event == null || event.shipmentId() == null || event.projectId() == null) {
            return;
        }
        emitShipment(TYPE_SHIPPED, event.shipmentId(), event.projectId(), event.actorUserId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onEtaDelayed(ShipmentEtaDelayedEvent event) {
        if (event == null || event.shipmentId() == null || event.projectId() == null
                || event.oldEta() == null || event.newEta() == null) {
            return;
        }
        if (ChronoUnit.DAYS.between(event.oldEta(), event.newEta()) < DELAY_THRESHOLD_DAYS) {
            return;
        }
        emitShipment(TYPE_DELAYED, event.shipmentId(), event.projectId(), event.actorUserId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDowntimeRisk(ShipmentDowntimeRiskEvent event) {
        if (event == null || event.shipmentId() == null || event.projectId() == null) {
            return;
        }
        emitShipment(TYPE_DOWNTIME_RISK, event.shipmentId(), event.projectId(), event.actorUserId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderByMissed(PositionOrderByMissedEvent event) {
        if (event == null || event.projectId() == null) {
            return;
        }
        Long projectId = event.projectId();
        emit(TYPE_ORDER_BY, "/deliveries?project=" + projectId,
                () -> members(projectId, MANAGER_ROLE), null, Set.of());
    }

    /** Managers get the manager link; foremen who are not managers get the foreman link. */
    private void emitShipment(String type, Long shipmentId, Long projectId, Long actorUserId) {
        Set<Long> managers = emit(type, "/deliveries/shipments?shipment=" + shipmentId,
                () -> members(projectId, MANAGER_ROLE), actorUserId, Set.of());
        emit(type, "/deliveries/upcoming?shipment=" + shipmentId,
                () -> members(projectId, FOREMAN_ROLE), actorUserId, managers);
    }

    /**
     * Resolves recipients (never throws), removes the actor and {@code exclude}, creates one notification per
     * user. Returns the resolved set (before removing the actor) so a manager is not re-notified as a foreman.
     */
    private Set<Long> emit(String type, String link, Supplier<Set<Long>> resolver, Long actorUserId,
                           Set<Long> exclude) {
        Set<Long> resolved;
        try {
            resolved = new LinkedHashSet<>(resolver.get());
        } catch (Exception e) {
            log.error("Failed to resolve recipients for {}", type, e);
            return Set.of();
        }
        Set<Long> recipients = new LinkedHashSet<>(resolved);
        recipients.removeAll(exclude);
        if (actorUserId != null) {
            recipients.remove(actorUserId);
        }
        for (Long userId : recipients) {
            try {
                notificationService.create(userId, type, null, link);
            } catch (Exception e) {
                log.error("Failed to emit notification {} to user {}", type, userId, e);
            }
        }
        return resolved;
    }

    private Set<Long> members(Long projectId, String roleCode) {
        Set<Long> ids = new LinkedHashSet<>();
        List<ProjectMemberEntity> members = projectMemberDao.findByProjectIdWithUserAndRole(projectId);
        if (members == null) {
            return ids;
        }
        for (ProjectMemberEntity m : members) {
            if (m == null || m.getAssignmentStatus() != AssignmentStatus.ACTIVE
                    || m.getProjectRole() == null || m.getUser() == null
                    || !Objects.equals(roleCode, m.getProjectRole().getCode())
                    || m.getUser().getId() == null) {
                continue;
            }
            ids.add(m.getUser().getId());
        }
        return ids;
    }
}
