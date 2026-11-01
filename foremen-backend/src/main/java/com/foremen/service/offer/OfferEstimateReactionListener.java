package com.foremen.service.offer;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.service.estimate.recalc.EstimateChangedEvent;

/**
 * FOR-05-07 additional tasks 3–4 — runs the offer reaction after an estimate change committed and
 * notifies the project's CLIENT, MANAGER(s) and SALESMAN (the only notifications of this batch).
 * Best effort: a failure is logged and never affects the committed estimate change.
 */
@Component
public class OfferEstimateReactionListener {

    private static final Logger log = LoggerFactory.getLogger(OfferEstimateReactionListener.class);

    /** Notification_Type i18n key: the client-visible offer was withdrawn by an estimate change. */
    static final String TYPE_WITHDRAWN = "notification.offer.estimateChanged.withdrawn";
    /** Notification_Type i18n key: a new offer version was prepared after an estimate change. */
    static final String TYPE_NEW_VERSION = "notification.offer.estimateChanged.newVersion";

    private static final Set<String> RECIPIENT_ROLES = Set.of("CLIENT", "MANAGER", "SALESMAN");

    private final OfferEstimateReactionService reactionService;
    private final NotificationService notificationService;
    private final ProjectMemberDao projectMemberDao;

    public OfferEstimateReactionListener(OfferEstimateReactionService reactionService,
                                         NotificationService notificationService,
                                         ProjectMemberDao projectMemberDao) {
        this.reactionService = reactionService;
        this.notificationService = notificationService;
        this.projectMemberDao = projectMemberDao;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onEstimateChanged(EstimateChangedEvent event) {
        OfferEstimateReactionService.Reaction reaction;
        try {
            reaction = reactionService.react(event);
        } catch (RuntimeException e) {
            log.error("Offer reaction to estimate change failed for project {}", event.projectId(), e);
            return;
        }
        if (reaction == null) {
            return;
        }
        String type = reaction.kind() == OfferEstimateImpact.WITHDRAW ? TYPE_WITHDRAWN : TYPE_NEW_VERSION;
        String deepLink = "/projects/" + reaction.projectId() + "/pricing";
        for (Long recipient : recipients(reaction.projectId(), event.actingUserId())) {
            try {
                notificationService.create(recipient, type, null, deepLink);
            } catch (RuntimeException e) {
                log.error("Failed to notify user {} of offer reaction {} (project {})",
                        recipient, reaction.kind(), reaction.projectId(), e);
            }
        }
    }

    /** ACTIVE CLIENT / MANAGER / SALESMAN members, de-duplicated, excluding the acting user. */
    private Set<Long> recipients(Long projectId, Long actingUserId) {
        Set<Long> ids = new LinkedHashSet<>();
        for (ProjectMemberEntity member : projectMemberDao.findByProjectIdWithUserAndRole(projectId)) {
            if (member.getAssignmentStatus() != AssignmentStatus.ACTIVE
                    || member.getProjectRole() == null || member.getUser() == null
                    || !RECIPIENT_ROLES.contains(member.getProjectRole().getCode())) {
                continue;
            }
            Long userId = member.getUser().getId();
            if (userId != null && !Objects.equals(userId, actingUserId)) {
                ids.add(userId);
            }
        }
        return ids;
    }
}
