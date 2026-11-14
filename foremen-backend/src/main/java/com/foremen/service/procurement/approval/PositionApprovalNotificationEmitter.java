package com.foremen.service.procurement.approval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.offer.NotificationService;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-11-02 (Requirement 15) - best-effort consumer turning position-approval events into in-app
 * notifications through {@link NotificationService#create} (emit only). Mirrors
 * {@code OfferNotificationEmitter}: AFTER_COMMIT with {@code fallbackExecution = true}, every
 * recipient wrapped in try/catch, recipients de-duplicated per event, the actor removed.
 * Decisions recorded on behalf of the client notify nobody (R15.5).
 */
@Slf4j
@Component
public class PositionApprovalNotificationEmitter {

    static final String TYPE_DECISION_REQUESTED = "notification.position.decisionRequested";
    static final String TYPE_ANALOG_PROPOSED = "notification.position.analogProposed";
    static final String TYPE_ACCEPTED = "notification.position.accepted";
    static final String TYPE_REJECTED = "notification.position.rejected";
    static final String TYPE_CLIENT_OWN = "notification.position.clientOwn";
    static final String TYPE_SUGGESTION_RECEIVED = "notification.position.suggestionReceived";

    static final String CLIENT_ROLE = "CLIENT";
    static final String MANAGER_ROLE = "MANAGER";
    static final String BUYER_ROLE = "BUYER";

    private final NotificationService notificationService;
    private final ProjectMemberDao projectMemberDao;
    private final UserDao userDao;
    private final PurchasePositionDao purchasePositionDao;

    public PositionApprovalNotificationEmitter(NotificationService notificationService,
                                               ProjectMemberDao projectMemberDao,
                                               UserDao userDao,
                                               PurchasePositionDao purchasePositionDao) {
        this.notificationService = notificationService;
        this.projectMemberDao = projectMemberDao;
        this.userDao = userDao;
        this.purchasePositionDao = purchasePositionDao;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSentForDecision(PositionsSentForDecisionEvent event) {
        if (event == null || event.projectId() == null) {
            return;
        }
        emit(event.projectId(), TYPE_DECISION_REQUESTED, tabLink(event.projectId()),
                () -> members(event.projectId(), CLIENT_ROLE), null);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAnalogProposed(PositionAnalogProposedEvent event) {
        if (event == null || event.projectId() == null) {
            return;
        }
        emit(event.projectId(), TYPE_ANALOG_PROPOSED, positionLink(event.projectId(), event.positionId()),
                () -> members(event.projectId(), CLIENT_ROLE), null);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDecision(PositionDecisionEvent event) {
        if (event == null || event.projectId() == null || event.outcome() == null || event.onBehalf()) {
            return;
        }
        String link = positionLink(event.projectId(), event.positionId());
        switch (event.outcome()) {
            case ACCEPTED -> emit(event.projectId(), TYPE_ACCEPTED, link,
                    () -> members(event.projectId(), MANAGER_ROLE), event.actorUserId());
            case REJECTED -> emit(event.projectId(), TYPE_REJECTED, link,
                    () -> members(event.projectId(), MANAGER_ROLE), event.actorUserId());
            case CLIENT_OWN -> emit(event.projectId(), TYPE_CLIENT_OWN, link, () -> {
                Set<Long> ids = members(event.projectId(), MANAGER_ROLE);
                ids.addAll(buyers(event.positionId()));
                return ids;
            }, event.actorUserId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSuggestion(PositionSuggestionEvent event) {
        if (event == null || event.projectId() == null) {
            return;
        }
        emit(event.projectId(), TYPE_SUGGESTION_RECEIVED, positionLink(event.projectId(), event.positionId()),
                () -> members(event.projectId(), MANAGER_ROLE), null);
    }

    /** Resolves recipients (never throws), removes the actor, and creates one notification per user. */
    private void emit(Long projectId, String type, String link, Supplier<Set<Long>> resolver, Long actorUserId) {
        Set<Long> recipients;
        try {
            recipients = new LinkedHashSet<>(resolver.get());
        } catch (Exception e) {
            log.error("Failed to resolve recipients for {} (project {})", type, projectId, e);
            return;
        }
        if (actorUserId != null) {
            recipients.remove(actorUserId);
        }
        for (Long userId : recipients) {
            try {
                notificationService.create(userId, type, null, link);
            } catch (Exception e) {
                log.error("Failed to emit notification {} to user {} (project {})", type, userId, projectId, e);
            }
        }
    }

    /** ACTIVE members of the project with the given project role code, as a mutable ordered id set. */
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

    /** The request's assigned buyer, else every active BUYER user. */
    private Set<Long> buyers(Long positionId) {
        Set<Long> ids = new LinkedHashSet<>();
        Long assigned = positionId == null ? null : purchasePositionDao.findAssignedBuyerId(positionId);
        if (assigned != null) {
            ids.add(assigned);
            return ids;
        }
        List<UserEntity> users = userDao.findActiveByRoleCode(BUYER_ROLE);
        if (users != null) {
            for (UserEntity u : users) {
                if (u != null && u.getId() != null) {
                    ids.add(u.getId());
                }
            }
        }
        return ids;
    }

    private static String tabLink(Long projectId) {
        return "/projects/" + projectId + "/positionApprovals";
    }

    private static String positionLink(Long projectId, Long positionId) {
        return positionId == null ? tabLink(projectId) : tabLink(projectId) + "?position=" + positionId;
    }
}
