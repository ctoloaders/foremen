package com.foremen.service.payment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.offer.NotificationService;

/**
 * Plain Mockito unit tests for {@link PaymentNotificationEmitter} (FOR-06-07 task 8.1), exercising
 * the best-effort consumer in isolation with a mocked {@link NotificationService},
 * {@link ProjectMemberDao}, and {@link PaymentScheduleDao} — following the
 * {@code DocumentNotificationEmitterTest} convention (a {@code @ExtendWith(MockitoExtension.class)}
 * plain-object unit test, no Spring context).
 *
 * <p>Covered — the Requirement 9.2 recipient mapping:
 * <ul>
 *   <li>the five internal triggers ({@code received} / {@code tranchePaid} / {@code trancheOverdue} /
 *       {@code amountMismatch} / {@code onlineFailed}) notify the project's <b>MANAGER + FINANCIER</b>
 *       ACTIVE members and deep-link to the Płatności tab;</li>
 *   <li>{@code schedulePublished} notifies the project's <b>CLIENT</b> ACTIVE member and deep-links to
 *       the FOR-09 client surface — and does <em>not</em> notify MANAGER/FINANCIER;</li>
 *   <li>INACTIVE members and other roles (WORKER/FOREMAN) are never notified.</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 9.1, 9.2</b>
 */
@ExtendWith(MockitoExtension.class)
class PaymentNotificationEmitterTest {

    private static final Long SCHEDULE_ID = 7L;
    private static final Long PROJECT_ID = 42L;

    private static final Long MANAGER_UID = 11L;
    private static final Long FINANCIER_UID = 12L;
    private static final Long CLIENT_UID = 13L;
    private static final Long FOREMAN_UID = 14L;
    private static final Long INACTIVE_MANAGER_UID = 15L;

    private static final String PLATNOSCI_DEEP_LINK = "/projects/42/payments";
    private static final String CLIENT_DEEP_LINK = "/portal/projects/42/payments";

    @Mock
    private NotificationService notificationService;
    @Mock
    private ProjectMemberDao projectMemberDao;
    @Mock
    private PaymentScheduleDao paymentScheduleDao;

    private PaymentNotificationEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = new PaymentNotificationEmitter(notificationService, projectMemberDao, paymentScheduleDao);
    }

    private ProjectMemberEntity member(Long userId, String roleCode, AssignmentStatus status) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        UserEntity user = new UserEntity();
        user.setId(userId);
        member.setUser(user);
        RoleEntity role = new RoleEntity();
        role.setCode(roleCode);
        member.setProjectRole(role);
        member.setAssignmentStatus(status);
        return member;
    }

    /** A mixed member roster: one ACTIVE each of MANAGER/FINANCIER/CLIENT/FOREMAN + an INACTIVE MANAGER. */
    private void stubRoster() {
        when(projectMemberDao.findByProjectId(PROJECT_ID)).thenReturn(List.of(
                member(MANAGER_UID, "MANAGER", AssignmentStatus.ACTIVE),
                member(FINANCIER_UID, "FINANCIER", AssignmentStatus.ACTIVE),
                member(CLIENT_UID, "CLIENT", AssignmentStatus.ACTIVE),
                member(FOREMAN_UID, "FOREMAN", AssignmentStatus.ACTIVE),
                member(INACTIVE_MANAGER_UID, "MANAGER", AssignmentStatus.INACTIVE)));
    }

    private void stubScheduleProject() {
        PaymentScheduleEntity schedule = new PaymentScheduleEntity();
        schedule.setId(SCHEDULE_ID);
        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);
        schedule.setProject(project);
        lenient().when(paymentScheduleDao.findById(SCHEDULE_ID)).thenReturn(Optional.of(schedule));
    }

    // --- internal triggers -> MANAGER + FINANCIER, Płatności deep-link (R9.2) ---

    @Test
    @DisplayName("received notifies only ACTIVE MANAGER + FINANCIER with the Płatności deep-link (R9.2 row 1)")
    void receivedNotifiesManagerAndFinancier() {
        stubRoster();

        emitter.onPaymentReceived(new PaymentReceivedEvent(SCHEDULE_ID, PROJECT_ID));

        verify(notificationService).create(eq(MANAGER_UID),
                eq("notification.payment.received"), isNull(), eq(PLATNOSCI_DEEP_LINK));
        verify(notificationService).create(eq(FINANCIER_UID),
                eq("notification.payment.received"), isNull(), eq(PLATNOSCI_DEEP_LINK));
        // No CLIENT / FOREMAN / INACTIVE recipient.
        verify(notificationService, never()).create(eq(CLIENT_UID), anyString(), isNull(), anyString());
        verify(notificationService, never()).create(eq(FOREMAN_UID), anyString(), isNull(), anyString());
        verify(notificationService, never()).create(eq(INACTIVE_MANAGER_UID), anyString(), isNull(), anyString());
        verify(notificationService, times(2)).create(anyLong(), anyString(), isNull(), anyString());
    }

    @Test
    @DisplayName("tranchePaid notifies MANAGER + FINANCIER with the Płatności deep-link (R9.2 row 2)")
    void tranchePaidNotifiesManagerAndFinancier() {
        stubRoster();

        emitter.onTranchePaid(new TranchePaidEvent(100L, SCHEDULE_ID, PROJECT_ID));

        verify(notificationService).create(eq(MANAGER_UID),
                eq("notification.payment.tranchePaid"), isNull(), eq(PLATNOSCI_DEEP_LINK));
        verify(notificationService).create(eq(FINANCIER_UID),
                eq("notification.payment.tranchePaid"), isNull(), eq(PLATNOSCI_DEEP_LINK));
        verify(notificationService, times(2)).create(anyLong(), anyString(), isNull(), anyString());
    }

    @Test
    @DisplayName("amountMismatch notifies MANAGER + FINANCIER with the Płatności deep-link (R9.2 row 4)")
    void amountMismatchNotifiesManagerAndFinancier() {
        stubRoster();

        emitter.onAmountMismatch(new AmountMismatchEvent(SCHEDULE_ID, PROJECT_ID));

        verify(notificationService).create(eq(MANAGER_UID),
                eq("notification.payment.amountMismatch"), isNull(), eq(PLATNOSCI_DEEP_LINK));
        verify(notificationService).create(eq(FINANCIER_UID),
                eq("notification.payment.amountMismatch"), isNull(), eq(PLATNOSCI_DEEP_LINK));
        verify(notificationService, times(2)).create(anyLong(), anyString(), isNull(), anyString());
    }

    @Test
    @DisplayName("trancheOverdue resolves the project from the schedule and notifies MANAGER + FINANCIER (R9.2 row 3)")
    void trancheOverdueResolvesProjectAndNotifiesManagerAndFinancier() {
        stubScheduleProject();
        stubRoster();

        emitter.onTrancheOverdue(new TrancheOverdueEvent(100L, SCHEDULE_ID));

        verify(notificationService).create(eq(MANAGER_UID),
                eq("notification.payment.trancheOverdue"), isNull(), eq(PLATNOSCI_DEEP_LINK));
        verify(notificationService).create(eq(FINANCIER_UID),
                eq("notification.payment.trancheOverdue"), isNull(), eq(PLATNOSCI_DEEP_LINK));
        verify(notificationService, times(2)).create(anyLong(), anyString(), isNull(), anyString());
    }

    @Test
    @DisplayName("onlineFailed resolves the project from the schedule and notifies MANAGER + FINANCIER (R9.2 row 6)")
    void onlineFailedResolvesProjectAndNotifiesManagerAndFinancier() {
        stubScheduleProject();
        stubRoster();

        emitter.onOnlineFailed(new PaymentTransactionService.OnlineFailedEvent(
                500L, SCHEDULE_ID, TransactionStatus.FAILED));

        verify(notificationService).create(eq(MANAGER_UID),
                eq("notification.payment.onlineFailed"), isNull(), eq(PLATNOSCI_DEEP_LINK));
        verify(notificationService).create(eq(FINANCIER_UID),
                eq("notification.payment.onlineFailed"), isNull(), eq(PLATNOSCI_DEEP_LINK));
        verify(notificationService, times(2)).create(anyLong(), anyString(), isNull(), anyString());
    }

    // --- schedulePublished -> CLIENT only, FOR-09 deep-link (R9.2 row 5) ---

    @Test
    @DisplayName("schedulePublished notifies only the CLIENT with the FOR-09 deep-link, not MANAGER/FINANCIER (R9.2 row 5)")
    void schedulePublishedNotifiesClientOnly() {
        stubRoster();

        emitter.onSchedulePublished(new SchedulePublishedEvent(SCHEDULE_ID, PROJECT_ID));

        verify(notificationService).create(eq(CLIENT_UID),
                eq("notification.payment.schedulePublished"), isNull(), eq(CLIENT_DEEP_LINK));
        verify(notificationService, never()).create(eq(MANAGER_UID), anyString(), isNull(), anyString());
        verify(notificationService, never()).create(eq(FINANCIER_UID), anyString(), isNull(), anyString());
        verify(notificationService, times(1)).create(anyLong(), anyString(), isNull(), anyString());
    }

    // --- edge cases ---

    @Test
    @DisplayName("a null event is a no-op across every listener (R9.1)")
    void nullEventsAreNoOps() {
        assertThatCode(() -> {
            emitter.onPaymentReceived(null);
            emitter.onTranchePaid(null);
            emitter.onTrancheOverdue(null);
            emitter.onAmountMismatch(null);
            emitter.onSchedulePublished(null);
            emitter.onOnlineFailed(null);
        }).doesNotThrowAnyException();

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("an overdue event whose schedule no longer resolves notifies nobody (R9.1 best-effort)")
    void overdueWithUnresolvedScheduleNotifiesNobody() {
        when(paymentScheduleDao.findById(SCHEDULE_ID)).thenReturn(Optional.empty());

        emitter.onTrancheOverdue(new TrancheOverdueEvent(100L, SCHEDULE_ID));

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("a failing NotificationService.create is swallowed and never starves the other recipient (R9.1, R9.5)")
    void failingCreateIsSwallowedAndDoesNotStarveOthers() {
        stubRoster();
        doThrow(new RuntimeException("boom"))
                .when(notificationService).create(eq(MANAGER_UID), anyString(), isNull(), anyString());

        assertThatCode(() -> emitter.onPaymentReceived(new PaymentReceivedEvent(SCHEDULE_ID, PROJECT_ID)))
                .doesNotThrowAnyException();

        // MANAGER threw, but FINANCIER is still attempted.
        verify(notificationService).create(eq(MANAGER_UID), anyString(), isNull(), anyString());
        verify(notificationService).create(eq(FINANCIER_UID), anyString(), isNull(), anyString());
    }
}
