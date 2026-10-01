package com.foremen.service.offer;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;

import lombok.extern.slf4j.Slf4j;

/**
 * Best-effort consumer that turns an {@link OfferNegotiationNotificationEvent} into an in-app
 * {@code Notification} via the generic {@link NotificationService} (FOR-05-07, Requirement 14;
 * design §OfferNotificationEmitter). It is the <b>first concrete consumer</b> of the otherwise
 * offer-decoupled notification service, wiring the two Requirement 14 triggers:
 *
 * <ul>
 *   <li><b>Client discount request / new round ⇒ MANAGER.</b> On
 *       {@link OfferNegotiationNotificationEvent.Trigger#DISCOUNT_REQUEST_TO_MANAGER} the project's
 *       MANAGER member is resolved from {@link ProjectMemberDao} and notified with the
 *       {@link #TYPE_DISCOUNT_REQUEST} type and an Offer_Tab deep-link (R14.1).</li>
 *   <li><b>Manager proposal ⇒ requesting CLIENT.</b> On
 *       {@link OfferNegotiationNotificationEvent.Trigger#MANAGER_PROPOSAL_TO_CLIENT} the requesting
 *       client (the {@code createdBy} of the answered request round, carried on the event) is
 *       notified with {@link #TYPE_MANAGER_PROPOSAL} (R14.2).</li>
 *   <li><b>Manager rejection ⇒ requesting CLIENT.</b> On
 *       {@link OfferNegotiationNotificationEvent.Trigger#MANAGER_REJECT_TO_CLIENT} the requesting
 *       client is notified with {@link #TYPE_MANAGER_REJECT} (R14.3).</li>
 * </ul>
 *
 * <h2>Best-effort emission (R14.4 / R14.5 / R10.11)</h2>
 * The listener fires on {@link TransactionPhase#AFTER_COMMIT}, so a notification is created only
 * <em>after</em> the negotiation transition has durably committed — a failure here therefore cannot
 * roll it back. {@code fallbackExecution = true} lets the listener also run when there is no active
 * transaction (e.g. unit/property tests that publish outside a {@code @Transactional} boundary).
 * Every branch is wrapped so <b>any</b> exception (a missing recipient, a
 * {@code NotificationService} failure, …) is logged and swallowed — emission never propagates and
 * never blocks the triggering transition (mirroring {@code InvitationEmailDispatcher}).
 *
 * <p>The {@code type} values are pure {@code Notification_Type} i18n keys resolved to a localized
 * title on the frontend (Requirement 13.11); no backend {@code messages} entry is required for them.
 * The deep-link targets the reused {@code pricing} Offer_Tab of the project
 * ({@code /projects/{projectId}/pricing}, Requirement 8.1).
 */
@Component
@Slf4j
public class OfferNotificationEmitter {

    /** {@code Notification_Type} i18n key: a client opened a discount request (to the MANAGER, R14.1). */
    static final String TYPE_DISCOUNT_REQUEST = "notification.offer.discount.request";

    /** {@code Notification_Type} i18n key: the manager proposed a discount (to the CLIENT, R14.2). */
    static final String TYPE_MANAGER_PROPOSAL = "notification.offer.manager.proposal";

    /** {@code Notification_Type} i18n key: the manager rejected the request (to the CLIENT, R14.3). */
    static final String TYPE_MANAGER_REJECT = "notification.offer.manager.reject";

    /** The project role code of the offer's executor recipient (the discount-request recipient). */
    private static final String MANAGER_ROLE = "MANAGER";

    private final NotificationService notificationService;
    private final ProjectMemberDao projectMemberDao;

    public OfferNotificationEmitter(NotificationService notificationService,
                                    ProjectMemberDao projectMemberDao) {
        this.notificationService = notificationService;
        this.projectMemberDao = projectMemberDao;
    }

    /**
     * Emits the in-app notification for an offer-negotiation trigger, best effort. Runs after the
     * negotiation transaction commits so it can never roll it back (R14.4/R14.5); any exception is
     * logged and swallowed (R10.11).
     *
     * @param event the negotiation-notification event published by {@link NegotiationService}
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOfferNegotiation(OfferNegotiationNotificationEvent event) {
        try {
            switch (event.trigger()) {
                case DISCOUNT_REQUEST_TO_MANAGER -> notifyManager(event);
                case MANAGER_PROPOSAL_TO_CLIENT -> notifyRequestingClient(event, TYPE_MANAGER_PROPOSAL);
                case MANAGER_REJECT_TO_CLIENT -> notifyRequestingClient(event, TYPE_MANAGER_REJECT);
            }
        } catch (Exception e) {
            // R14.5 / R10.11: log-only on failure — never propagate, never block the committed
            // negotiation transition.
            log.error("Failed to emit offer-negotiation notification for offer {} (trigger {})",
                    event.offerId(), event.trigger(), e);
        }
    }

    /**
     * Notifies the project's MANAGER member that a client opened a discount request / new round
     * (R14.1). The MANAGER recipient is resolved from the project members (the first member whose
     * project role code is {@code MANAGER}); if the project has no MANAGER member no notification is
     * created (nothing to notify — still not an error).
     */
    private void notifyManager(OfferNegotiationNotificationEvent event) {
        Long managerUserId = resolveProjectManagerUserId(event.projectId());
        if (managerUserId == null) {
            log.warn("No MANAGER member to notify for offer {} (project {}); skipping discount-request "
                    + "notification", event.offerId(), event.projectId());
            return;
        }
        notificationService.create(
                managerUserId, TYPE_DISCOUNT_REQUEST, null, deepLink(event.projectId()));
    }

    /**
     * Notifies the requesting CLIENT (the {@code createdBy} of the answered request round, carried on
     * the event) of the manager's proposal/rejection (R14.2/R14.3). If the requesting client id is
     * unknown, no notification is created.
     */
    private void notifyRequestingClient(OfferNegotiationNotificationEvent event, String type) {
        Long clientUserId = event.requestingClientUserId();
        if (clientUserId == null) {
            log.warn("No requesting-client recipient for offer {} (trigger {}); skipping notification",
                    event.offerId(), event.trigger());
            return;
        }
        notificationService.create(clientUserId, type, null, deepLink(event.projectId()));
    }

    /**
     * Resolves the user id of the project's MANAGER member — the first project member whose project
     * role code is {@code MANAGER} (R14.1). Returns {@code null} when the project has no such member.
     */
    private Long resolveProjectManagerUserId(Long projectId) {
        if (projectId == null) {
            return null;
        }
        List<ProjectMemberEntity> members = projectMemberDao.findByProjectId(projectId);
        for (ProjectMemberEntity member : members) {
            RoleEntity role = member.getProjectRole();
            if (role != null && MANAGER_ROLE.equals(role.getCode())) {
                UserEntity user = member.getUser();
                if (user != null && user.getId() != null) {
                    return user.getId();
                }
            }
        }
        return null;
    }

    /** The Offer_Tab deep-link for a project — the reused {@code pricing} tab route (R8.1). */
    private static String deepLink(Long projectId) {
        return projectId == null ? null : "/projects/" + projectId + "/pricing";
    }
}
