package com.foremen.service.procurement.receipt;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.offer.NotificationService;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-11-05 (Requirement 12) - best-effort consumer turning {@link GoodsReceiptDiscrepancyEvent} into in-app
 * notifications. Mirrors {@code ShipmentNotificationEmitter}: AFTER_COMMIT with {@code fallbackExecution = true},
 * every resolution and emission wrapped in try/catch + {@code log.error}; nothing is thrown.
 *
 * <p>Recipients: ACTIVE {@code MANAGER} project members plus all active {@code BUYER} users, de-duplicated,
 * minus the actor (see {@link GoodsReceiptRules#recipients}).
 */
@Slf4j
@Component
public class GoodsReceiptNotificationEmitter {

    static final String TYPE_DISCREPANCY = "notification.receipt.discrepancy";
    static final String MANAGER_ROLE = "MANAGER";
    static final String BUYER_ROLE = "BUYER";

    private final NotificationService notificationService;
    private final ProjectMemberDao projectMemberDao;
    private final UserDao userDao;

    public GoodsReceiptNotificationEmitter(NotificationService notificationService,
                                           ProjectMemberDao projectMemberDao, UserDao userDao) {
        this.notificationService = notificationService;
        this.projectMemberDao = projectMemberDao;
        this.userDao = userDao;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDiscrepancy(GoodsReceiptDiscrepancyEvent event) {
        if (event == null || event.positionId() == null || event.projectId() == null) {
            return;
        }
        Set<Long> recipients;
        try {
            recipients = GoodsReceiptRules.recipients(
                    managers(event.projectId()), buyers(), event.actorUserId());
        } catch (Exception e) {
            log.error("Failed to resolve recipients for {}", TYPE_DISCREPANCY, e);
            return;
        }
        if (recipients == null) {
            return;
        }
        String link = "/deliveries/receipt?position=" + event.positionId();
        for (Long userId : recipients) {
            try {
                notificationService.create(userId, TYPE_DISCREPANCY, null, link);
            } catch (Exception e) {
                log.error("Failed to emit notification {} to user {}", TYPE_DISCREPANCY, userId, e);
            }
        }
    }

    private Set<Long> managers(Long projectId) {
        Set<Long> ids = new LinkedHashSet<>();
        List<ProjectMemberEntity> members = projectMemberDao.findByProjectIdWithUserAndRole(projectId);
        if (members == null) {
            return ids;
        }
        for (ProjectMemberEntity m : members) {
            if (m == null || m.getAssignmentStatus() != AssignmentStatus.ACTIVE
                    || m.getProjectRole() == null || m.getUser() == null
                    || !Objects.equals(MANAGER_ROLE, m.getProjectRole().getCode())
                    || m.getUser().getId() == null) {
                continue;
            }
            ids.add(m.getUser().getId());
        }
        return ids;
    }

    private Set<Long> buyers() {
        Set<Long> ids = new LinkedHashSet<>();
        List<UserEntity> users = userDao.findActiveByRoleCode(BUYER_ROLE);
        if (users == null) {
            return ids;
        }
        for (UserEntity u : users) {
            if (u != null && u.getId() != null) {
                ids.add(u.getId());
            }
        }
        return ids;
    }
}
