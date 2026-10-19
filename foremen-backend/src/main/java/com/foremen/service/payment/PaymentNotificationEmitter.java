package com.foremen.service.payment;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.offer.NotificationService;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-06-07 (Requirement 9; design §PaymentNotificationEmitter): the best-effort consumer that turns
 * the six payment transition events into in-app {@code Notification}s via the generic FOR-05-07
 * {@link NotificationService}. This module persists / delivers nothing itself — it is purely a
 * <b>consumer</b> of the generic notification extension point and adds no new delivery channel
 * (R9.1).
 *
 * <p>It wires the six Requirement 9.2 triggers. Five notify the project's <b>MANAGER + FINANCIER</b>
 * members and deep-link to the internal Płatności tab; the {@code schedulePublished} trigger notifies
 * the project's <b>CLIENT</b> member(s) and deep-links to the FOR-09 client surface (R9.2):
 *
 * <table>
 *   <caption>Requirement 9.2 notification map</caption>
 *   <tr><th>Event</th><th>Type (i18n key)</th><th>Recipient(s)</th><th>Deep-link</th></tr>
 *   <tr><td>{@link PaymentReceivedEvent}</td><td>{@link #TYPE_RECEIVED}</td><td>MANAGER + FINANCIER</td><td>Płatności tab</td></tr>
 *   <tr><td>{@link TranchePaidEvent}</td><td>{@link #TYPE_TRANCHE_PAID}</td><td>MANAGER + FINANCIER</td><td>Płatności tab</td></tr>
 *   <tr><td>{@link TrancheOverdueEvent}</td><td>{@link #TYPE_TRANCHE_OVERDUE}</td><td>MANAGER + FINANCIER</td><td>Płatności tab</td></tr>
 *   <tr><td>{@link AmountMismatchEvent}</td><td>{@link #TYPE_AMOUNT_MISMATCH}</td><td>MANAGER + FINANCIER</td><td>Płatności tab</td></tr>
 *   <tr><td>{@link SchedulePublishedEvent}</td><td>{@link #TYPE_SCHEDULE_PUBLISHED}</td><td>CLIENT</td><td>FOR-09 client surface</td></tr>
 *   <tr><td>{@link PaymentTransactionService.OnlineFailedEvent}</td><td>{@link #TYPE_ONLINE_FAILED}</td><td>MANAGER + FINANCIER</td><td>Płatności tab</td></tr>
 * </table>
 *
 * <p>The recipient set is resolved here (not at publish time) from {@link ProjectMemberDao}: the
 * project's ACTIVE members whose project role code matches the trigger's audience. The
 * {@link TrancheOverdueEvent} and {@link PaymentTransactionService.OnlineFailedEvent} carry no
 * {@code projectId}; the emitter resolves it from their {@code scheduleId} through
 * {@link PaymentScheduleDao} (the schedule's owning project). (The other four events carry the
 * {@code projectId} directly from their publisher.)
 *
 * <h2>Best-effort emission (R9.1, R9.5)</h2>
 * Every listener fires on {@link TransactionPhase#AFTER_COMMIT}, so a notification is created only
 * <em>after</em> the triggering payment/tranche transition has durably committed — a failure here
 * therefore cannot roll it back (R9.5). {@code fallbackExecution = true} lets the listener also run
 * when there is no active transaction (e.g. unit tests that publish outside a {@code @Transactional}
 * boundary). Every recipient is wrapped individually so <b>any</b> exception (a missing recipient, a
 * {@code NotificationService} failure, …) is logged and swallowed — emission never propagates and
 * never blocks the triggering transition, and one failed recipient never starves the others
 * (mirroring {@code OfferNotificationEmitter} / {@code DocumentNotificationEmitter} /
 * {@code WorkReportNotificationEmitter}).
 *
 * <p>The {@code type} values are pure {@code Notification_Type} i18n keys resolved to a localized
 * title on the frontend (R9.4); no backend {@code messages} entry is required for them (the PL/RU
 * title wiring is task 10.1).
 */
@Component
@Slf4j
public class PaymentNotificationEmitter {

    /** {@code Notification_Type} i18n key: a confirmed payment was allocated (to MANAGER + FINANCIER, R9.2 row 1). */
    static final String TYPE_RECEIVED = "notification.payment.received";

    /** {@code Notification_Type} i18n key: a tranche reached PAID (to MANAGER + FINANCIER, R9.2 row 2). */
    static final String TYPE_TRANCHE_PAID = "notification.payment.tranchePaid";

    /** {@code Notification_Type} i18n key: a tranche first crossed into OVERDUE (to MANAGER + FINANCIER, R9.2 row 3). */
    static final String TYPE_TRANCHE_OVERDUE = "notification.payment.trancheOverdue";

    /** {@code Notification_Type} i18n key: an under/overpayment occurred (to MANAGER + FINANCIER, R9.2 row 4). */
    static final String TYPE_AMOUNT_MISMATCH = "notification.payment.amountMismatch";

    /** {@code Notification_Type} i18n key: the schedule was published (to CLIENT, R9.2 row 5). */
    static final String TYPE_SCHEDULE_PUBLISHED = "notification.payment.schedulePublished";

    /** {@code Notification_Type} i18n key: an online payment failed/was reversed (to MANAGER + FINANCIER, R9.2 row 6). */
    static final String TYPE_ONLINE_FAILED = "notification.payment.onlineFailed";

    /** The MANAGER project-role code — one of the internal recipients (R9.2). */
    private static final String MANAGER_ROLE = "MANAGER";

    /** The FINANCIER project-role code — one of the internal recipients (R9.2). */
    private static final String FINANCIER_ROLE = "FINANCIER";

    /** The CLIENT project-role code — the schedulePublished recipient (R9.2 row 5). */
    private static final String CLIENT_ROLE = "CLIENT";

    /** The Płatności tab key the internal deep-links target ({@code /projects/{projectId}/payments}, R9.2). */
    private static final String PAYMENTS_TAB = "payments";

    private final NotificationService notificationService;
    private final ProjectMemberDao projectMemberDao;
    private final PaymentScheduleDao paymentScheduleDao;

    public PaymentNotificationEmitter(NotificationService notificationService,
                                      ProjectMemberDao projectMemberDao,
                                      PaymentScheduleDao paymentScheduleDao) {
        this.notificationService = notificationService;
        this.projectMemberDao = projectMemberDao;
        this.paymentScheduleDao = paymentScheduleDao;
    }

    /** R9.2 row 1: a confirmed payment was allocated ⇒ notify MANAGER + FINANCIER (Płatności deep-link). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPaymentReceived(PaymentReceivedEvent event) {
        if (event == null) {
            return;
        }
        notifyInternal(event.projectId(), TYPE_RECEIVED, internalDeepLink(event.projectId()));
    }

    /** R9.2 row 2: a tranche reached PAID ⇒ notify MANAGER + FINANCIER (Płatności deep-link). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTranchePaid(TranchePaidEvent event) {
        if (event == null) {
            return;
        }
        notifyInternal(event.projectId(), TYPE_TRANCHE_PAID, internalDeepLink(event.projectId()));
    }

    /**
     * R9.2 row 3: a tranche first crossed into OVERDUE ⇒ notify MANAGER + FINANCIER (Płatności
     * deep-link). The {@link TrancheOverdueEvent} carries no {@code projectId}, so it is resolved from
     * the event's {@code scheduleId} via the schedule's owning project.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTrancheOverdue(TrancheOverdueEvent event) {
        if (event == null) {
            return;
        }
        Long projectId = projectIdForSchedule(event.scheduleId());
        notifyInternal(projectId, TYPE_TRANCHE_OVERDUE, internalDeepLink(projectId));
    }

    /** R9.2 row 4: an under/overpayment occurred ⇒ notify MANAGER + FINANCIER (Płatności deep-link). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAmountMismatch(AmountMismatchEvent event) {
        if (event == null) {
            return;
        }
        notifyInternal(event.projectId(), TYPE_AMOUNT_MISMATCH, internalDeepLink(event.projectId()));
    }

    /**
     * R9.2 row 5: the schedule was published ⇒ notify the project's CLIENT member(s), deep-linking to
     * the FOR-09 client surface rather than the internal Płatności tab.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSchedulePublished(SchedulePublishedEvent event) {
        if (event == null) {
            return;
        }
        Long projectId = event.projectId();
        notifyRole(projectId, CLIENT_ROLE, TYPE_SCHEDULE_PUBLISHED, clientDeepLink(projectId));
    }

    /**
     * R9.2 row 6: an online payment is FAILED or REFUNDED ⇒ notify MANAGER + FINANCIER (Płatności
     * deep-link). The {@link PaymentTransactionService.OnlineFailedEvent} carries no {@code projectId},
     * so it is resolved from the event's {@code scheduleId} (which may be {@code null} for an unlinked
     * online transaction — nothing is notified then).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOnlineFailed(PaymentTransactionService.OnlineFailedEvent event) {
        if (event == null) {
            return;
        }
        Long projectId = projectIdForSchedule(event.scheduleId());
        notifyInternal(projectId, TYPE_ONLINE_FAILED, internalDeepLink(projectId));
    }

    // --- recipient resolution + emission ---

    /** Notifies both internal audiences (MANAGER + FINANCIER) of the project (R9.2). */
    private void notifyInternal(Long projectId, String type, String deepLink) {
        notifyRoles(projectId, type, deepLink, MANAGER_ROLE, FINANCIER_ROLE);
    }

    /** Fans a notification out to every ACTIVE member of the project whose role is any of {@code roleCodes}. */
    private void notifyRoles(Long projectId, String type, String deepLink, String... roleCodes) {
        if (projectId == null) {
            return;
        }
        List<ProjectMemberEntity> members = projectMemberDao.findByProjectId(projectId);
        for (ProjectMemberEntity member : members) {
            if (isActiveMemberOfAnyRole(member, roleCodes)) {
                emitTo(recipientId(member), type, deepLink, projectId);
            }
        }
    }

    /** Fans a notification out to every ACTIVE member of the project whose role is {@code roleCode}. */
    private void notifyRole(Long projectId, String roleCode, String type, String deepLink) {
        notifyRoles(projectId, type, deepLink, roleCode);
    }

    /** True iff {@code member} is ACTIVE and its project role code is one of {@code roleCodes}. */
    private static boolean isActiveMemberOfAnyRole(ProjectMemberEntity member, String... roleCodes) {
        if (member == null || member.getAssignmentStatus() != AssignmentStatus.ACTIVE) {
            return false;
        }
        RoleEntity role = member.getProjectRole();
        String code = role == null ? null : role.getCode();
        if (code == null) {
            return false;
        }
        for (String candidate : roleCodes) {
            if (code.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static Long recipientId(ProjectMemberEntity member) {
        UserEntity user = member.getUser();
        return user == null ? null : user.getId();
    }

    /** Creates one notification for a single recipient, logging and swallowing any failure (R9.1, R9.5). */
    private void emitTo(Long recipientUserId, String type, String deepLink, Long projectId) {
        if (recipientUserId == null) {
            return;
        }
        try {
            notificationService.create(recipientUserId, type, null, deepLink);
        } catch (Exception e) {
            // R9.1/R9.5: log-only on failure — never propagate, never roll back the committed payment
            // transition, and never starve the remaining recipients.
            log.error("Failed to emit payment notification (type {}) for project {} to user {}",
                    type, projectId, recipientUserId, e);
        }
    }

    /**
     * Resolves the owning project id for the two {@code scheduleId}-only events
     * ({@link TrancheOverdueEvent}, {@link PaymentTransactionService.OnlineFailedEvent}) through
     * {@link PaymentScheduleDao}. A {@code null} schedule id — or a schedule / project that no longer
     * resolves — yields {@code null}, so nothing is notified (a silent no-op, consistent with the
     * best-effort contract, R9.5). The read runs on the {@code AFTER_COMMIT} thread after the
     * triggering transaction has committed, so the schedule row is visible.
     */
    private Long projectIdForSchedule(Long scheduleId) {
        if (scheduleId == null) {
            return null;
        }
        PaymentScheduleEntity schedule = paymentScheduleDao.findById(scheduleId).orElse(null);
        if (schedule == null || schedule.getProject() == null) {
            return null;
        }
        return schedule.getProject().getId();
    }

    /** The internal Płatności deep-link ({@code /projects/{projectId}/payments}, R9.2). */
    private static String internalDeepLink(Long projectId) {
        return projectId == null ? null : "/projects/" + projectId + "/" + PAYMENTS_TAB;
    }

    /** The CLIENT (FOR-09) deep-link for a published schedule ({@code /portal/projects/{projectId}/payments}, R9.2 row 5). */
    private static String clientDeepLink(Long projectId) {
        return projectId == null ? null : "/portal/projects/" + projectId + "/" + PAYMENTS_TAB;
    }
}
