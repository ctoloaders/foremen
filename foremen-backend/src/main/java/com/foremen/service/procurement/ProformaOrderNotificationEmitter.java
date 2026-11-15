package com.foremen.service.procurement;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.foremen.dao.ExternalOrderDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.bank.detect.LedgerMatchProposedEvent;
import com.foremen.service.offer.NotificationService;
import com.foremen.service.procurement.externalorder.ExternalOrderPaidEvent;
import com.foremen.service.procurement.externalorder.ExternalOrderSentEvent;
import com.foremen.service.procurement.proforma.ProformaPaidEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-11-03 (Requirement 19; design §Notifications) - best-effort consumer turning proforma and
 * external-order events into in-app notifications through {@link NotificationService#create} (emit only).
 * Mirrors {@code PositionApprovalNotificationEmitter}: AFTER_COMMIT with {@code fallbackExecution = true},
 * recipients de-duplicated per event, the actor removed, every resolution and emission wrapped in
 * try/catch + {@code log.error}.
 *
 * <table>
 *   <caption>Requirement 19 notification map</caption>
 *   <tr><th>Event</th><th>Type (i18n key)</th><th>Recipients</th><th>Deep link</th></tr>
 *   <tr><td>{@link ProformaPaidEvent}</td><td>{@link #TYPE_PROFORMA_PAID}</td><td>ACTIVE MANAGER members minus actor</td><td>{@code /projects/{p}/clientProformas?proforma={id}}</td></tr>
 *   <tr><td>{@link ProformaPaidEvent}</td><td>{@link #TYPE_RELEASED_TO_BUYER}</td><td>assigned buyers of the positions' requests, else active BUYER users; minus actor (once per proforma per recipient)</td><td>{@code /deliveries/external-orders?project={p}&proforma={id}}</td></tr>
 *   <tr><td>{@link ExternalOrderSentEvent}</td><td>{@link #TYPE_ORDER_SENT}</td><td>active FINANCIER users minus actor</td><td>{@code /deliveries/external-orders?order={id}}</td></tr>
 *   <tr><td>{@link ExternalOrderPaidEvent}</td><td>{@link #TYPE_ORDER_PAID}</td><td>assigned buyer, else creator, else active BUYER users; minus actor</td><td>{@code /deliveries/external-orders?order={id}}</td></tr>
 *   <tr><td>{@link LedgerMatchProposedEvent}</td><td>{@link #TYPE_MATCH_PROPOSED}</td><td>active FINANCIER users</td><td>{@code /company/bank-transactions?transactionId={id}}</td></tr>
 * </table>
 *
 * <p>The ledger deep link follows the route registered by the FOR-07-04 ledger page
 * ({@code /company/bank-transactions}, which {@code BankNotificationEmitter} also targets), not the
 * {@code /bank-transactions} string of the design draft.
 */
@Slf4j
@Component
public class ProformaOrderNotificationEmitter {

    static final String TYPE_PROFORMA_PAID = "notification.proforma.paid";
    static final String TYPE_RELEASED_TO_BUYER = "notification.order.releasedToBuyer";
    static final String TYPE_ORDER_SENT = "notification.externalOrder.sentForPayment";
    static final String TYPE_ORDER_PAID = "notification.externalOrder.paid";
    static final String TYPE_MATCH_PROPOSED = "notification.invoice.matchProposed";

    static final String MANAGER_ROLE = "MANAGER";
    static final String BUYER_ROLE = "BUYER";
    static final String FINANCIER_ROLE = "FINANCIER";

    private final NotificationService notificationService;
    private final ProjectMemberDao projectMemberDao;
    private final UserDao userDao;
    private final PurchasePositionDao purchasePositionDao;
    private final ExternalOrderDao externalOrderDao;

    public ProformaOrderNotificationEmitter(NotificationService notificationService,
                                            ProjectMemberDao projectMemberDao,
                                            UserDao userDao,
                                            PurchasePositionDao purchasePositionDao,
                                            ExternalOrderDao externalOrderDao) {
        this.notificationService = notificationService;
        this.projectMemberDao = projectMemberDao;
        this.userDao = userDao;
        this.purchasePositionDao = purchasePositionDao;
        this.externalOrderDao = externalOrderDao;
    }

    /** R19.2: a proforma was paid and released ⇒ managers (proforma.paid) and buyers (order.releasedToBuyer). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProformaPaid(ProformaPaidEvent event) {
        if (event == null || event.proformaId() == null) {
            return;
        }
        Long projectId = event.projectId();
        if (projectId != null) {
            emit(TYPE_PROFORMA_PAID,
                    "/projects/" + projectId + "/clientProformas?proforma=" + event.proformaId(),
                    () -> members(projectId, MANAGER_ROLE), event.actorUserId());
        }
        emit(TYPE_RELEASED_TO_BUYER,
                "/deliveries/external-orders?project=" + projectId + "&proforma=" + event.proformaId(),
                () -> positionBuyers(event.positionIds()), event.actorUserId());
    }

    /** R19.2: an external order was sent for payment ⇒ active FINANCIER users minus actor. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderSent(ExternalOrderSentEvent event) {
        if (event == null || event.externalOrderId() == null) {
            return;
        }
        emit(TYPE_ORDER_SENT, orderLink(event.externalOrderId()),
                () -> activeUsers(FINANCIER_ROLE), event.actorUserId());
    }

    /** R19.2: an external order was paid ⇒ assigned buyer, else creator, else active BUYER users; minus actor. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderPaid(ExternalOrderPaidEvent event) {
        if (event == null || event.externalOrderId() == null) {
            return;
        }
        emit(TYPE_ORDER_PAID, orderLink(event.externalOrderId()),
                () -> orderOwners(event.externalOrderId()), event.actorUserId());
    }

    /** R19.5: a ledger suggestion of the new kinds was stored ⇒ active FINANCIER users (no actor). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLedgerMatchProposed(LedgerMatchProposedEvent event) {
        if (event == null || event.transactionId() == null) {
            return;
        }
        emit(TYPE_MATCH_PROPOSED, "/company/bank-transactions?transactionId=" + event.transactionId(),
                () -> activeUsers(FINANCIER_ROLE), null);
    }

    // --- recipient resolution + emission ---

    /** Resolves recipients (never throws), removes the actor, and creates one notification per user. */
    private void emit(String type, String link, Supplier<Set<Long>> resolver, Long actorUserId) {
        Set<Long> recipients;
        try {
            recipients = new LinkedHashSet<>(resolver.get());
        } catch (Exception e) {
            log.error("Failed to resolve recipients for {}", type, e);
            return;
        }
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

    /** Active users of one company role. */
    private Set<Long> activeUsers(String roleCode) {
        Set<Long> ids = new LinkedHashSet<>();
        List<UserEntity> users = userDao.findActiveByRoleCode(roleCode);
        if (users != null) {
            for (UserEntity u : users) {
                if (u != null && u.getId() != null) {
                    ids.add(u.getId());
                }
            }
        }
        return ids;
    }

    /** Assigned buyers of the positions' purchase requests, else every active BUYER user. */
    private Set<Long> positionBuyers(List<Long> positionIds) {
        Set<Long> ids = new LinkedHashSet<>();
        if (positionIds != null) {
            for (Long positionId : positionIds) {
                Long assigned = positionId == null ? null : purchasePositionDao.findAssignedBuyerId(positionId);
                if (assigned != null) {
                    ids.add(assigned);
                }
            }
        }
        return ids.isEmpty() ? activeUsers(BUYER_ROLE) : ids;
    }

    /** The order's assigned buyer, else its creator (audit e-mail), else every active BUYER user. */
    private Set<Long> orderOwners(Long orderId) {
        Set<Long> ids = new LinkedHashSet<>();
        Long assigned = externalOrderDao.findAssignedBuyerId(orderId);
        if (assigned != null) {
            ids.add(assigned);
            return ids;
        }
        String createdBy = externalOrderDao.findCreatedBy(orderId);
        if (createdBy != null && !createdBy.isBlank()) {
            userDao.findByEmailIgnoreCase(createdBy.trim()).ifPresent(u -> {
                if (u.getId() != null && u.isActive()) {
                    ids.add(u.getId());
                }
            });
            if (!ids.isEmpty()) {
                return ids;
            }
        }
        return activeUsers(BUYER_ROLE);
    }

    private static String orderLink(Long orderId) {
        return "/deliveries/external-orders?order=" + orderId;
    }
}
