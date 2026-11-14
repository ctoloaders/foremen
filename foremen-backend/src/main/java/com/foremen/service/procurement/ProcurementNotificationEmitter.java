package com.foremen.service.procurement;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.offer.NotificationService;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-11-00 (Requirement 13) — best-effort consumer turning purchase-request events into in-app
 * notifications through {@link NotificationService#create} (emit only, no own delivery).
 *
 * <ul>
 *   <li>{@link PurchaseRequestAssignedEvent} ⇒ {@link #TYPE_ASSIGNED} to the newly assigned buyer (R13.1).</li>
 *   <li>{@link PurchaseRequestOverdueEvent} ⇒ {@link #TYPE_OVERDUE} to the assigned buyer (or all active
 *       {@code BUYER} users when unassigned) and the project's ACTIVE {@code MANAGER} members, each
 *       recipient at most once (R13.3).</li>
 * </ul>
 *
 * Both carry the deep link {@code /deliveries?request={id}} and a {@code null} body (R13.5). Listeners
 * are {@code AFTER_COMMIT} with {@code fallbackExecution = true}; any failure is logged and swallowed
 * and never rolls back the business transition (R13.6, R13.7).
 */
@Slf4j
@Component
public class ProcurementNotificationEmitter {

    static final String TYPE_ASSIGNED = "notification.request.assigned";
    static final String TYPE_OVERDUE = "notification.request.overdue";

    static final String BUYER_ROLE = "BUYER";
    static final String MANAGER_ROLE = "MANAGER";

    private static final String DEEP_LINK_PREFIX = "/deliveries?request=";

    private final NotificationService notificationService;
    private final UserDao userDao;
    private final ProjectMemberDao projectMemberDao;

    public ProcurementNotificationEmitter(NotificationService notificationService,
                                          UserDao userDao,
                                          ProjectMemberDao projectMemberDao) {
        this.notificationService = notificationService;
        this.userDao = userDao;
        this.projectMemberDao = projectMemberDao;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAssigned(PurchaseRequestAssignedEvent event) {
        if (event == null || event.requestId() == null || event.buyerUserId() == null) {
            return;
        }
        emitTo(event.buyerUserId(), TYPE_ASSIGNED, deepLink(event.requestId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOverdue(PurchaseRequestOverdueEvent event) {
        if (event == null || event.requestId() == null) {
            return;
        }
        Set<Long> recipients = new LinkedHashSet<>();
        try {
            if (event.assignedBuyerId() != null) {
                recipients.add(event.assignedBuyerId());
            } else {
                List<UserEntity> buyers = userDao.findByRoleCode(BUYER_ROLE);
                if (buyers != null) {
                    for (UserEntity user : buyers) {
                        if (user != null && user.isActive() && user.getId() != null) {
                            recipients.add(user.getId());
                        }
                    }
                }
            }
            if (event.projectId() != null) {
                List<ProjectMemberEntity> members = projectMemberDao.findByProjectId(event.projectId());
                if (members != null) {
                    for (ProjectMemberEntity member : members) {
                        Long id = activeManagerUserId(member);
                        if (id != null) {
                            recipients.add(id);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to resolve recipients for overdue purchase request {}", event.requestId(), e);
            return;
        }
        String link = deepLink(event.requestId());
        for (Long userId : recipients) {
            emitTo(userId, TYPE_OVERDUE, link);
        }
    }

    private static Long activeManagerUserId(ProjectMemberEntity member) {
        if (member == null || member.getAssignmentStatus() != AssignmentStatus.ACTIVE) {
            return null;
        }
        RoleEntity role = member.getProjectRole();
        if (role == null || !MANAGER_ROLE.equals(role.getCode())) {
            return null;
        }
        UserEntity user = member.getUser();
        return user == null ? null : user.getId();
    }

    private void emitTo(Long userId, String type, String deepLink) {
        try {
            notificationService.create(userId, type, null, deepLink);
        } catch (Exception e) {
            log.error("Failed to emit notification {} to user {}", type, userId, e);
        }
    }

    private static String deepLink(Long requestId) {
        return DEEP_LINK_PREFIX + requestId;
    }
}
