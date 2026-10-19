package com.foremen.service.payment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.foremen.dao.PaymentScheduleDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.PaymentScheduleEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.offer.NotificationService;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * FOR-06-07 task 8.1 — Property 9 "BestEffortEmission" for {@link PaymentNotificationEmitter}
 * (design §PaymentNotificationEmitter; Requirements 9.1, 9.5). Notification emission is a best-effort
 * side effect executed <b>after</b> the triggering transaction commits: a {@link NotificationService}
 * that throws must never propagate out of the emitter, so it can never roll back (or even reach) the
 * already-committed payment/tranche transition.
 *
 * <p>This property models a {@link NotificationService#create} that throws for <b>every</b>
 * recipient, then — for an arbitrary recipient roster (any mix of roles / ACTIVE / INACTIVE
 * members, 0..12 of them) — fires each of the six R9.2 triggers and asserts the listener completes
 * normally (no exception escapes). Because the production listeners run on
 * {@link org.springframework.transaction.event.TransactionPhase#AFTER_COMMIT}, a non-escaping
 * listener cannot affect the committed transition — exactly the best-effort guarantee (R9.1, R9.5).
 *
 * <p><b>Validates: Requirements 9.1, 9.5</b>
 */
@Tag("Feature: FOR-06-07-client-payments, Property 9 BestEffortEmission")
class BestEffortEmissionPropertyTest {

    private static final Long SCHEDULE_ID = 7L;
    private static final Long PROJECT_ID = 42L;

    private static final String[] ROLE_CODES = {"MANAGER", "FINANCIER", "CLIENT", "FOREMAN", "WORKER"};

    /** 0..12 members, each an arbitrary (role, status, userId) triple. */
    @Provide
    Arbitrary<List<ProjectMemberEntity>> rosters() {
        Arbitrary<Integer> roleIdx = Arbitraries.integers().between(0, ROLE_CODES.length - 1);
        Arbitrary<Boolean> active = Arbitraries.of(true, false);
        Arbitrary<Long> userId = Arbitraries.longs().between(1L, 1000L);
        Arbitrary<ProjectMemberEntity> member = net.jqwik.api.Combinators
                .combine(roleIdx, active, userId)
                .as((idx, isActive, uid) -> member(uid, ROLE_CODES[idx],
                        isActive ? AssignmentStatus.ACTIVE : AssignmentStatus.INACTIVE));
        return member.list().ofMinSize(0).ofMaxSize(12);
    }

    private static ProjectMemberEntity member(Long userId, String roleCode, AssignmentStatus status) {
        ProjectMemberEntity m = new ProjectMemberEntity();
        UserEntity user = new UserEntity();
        user.setId(userId);
        m.setUser(user);
        RoleEntity role = new RoleEntity();
        role.setCode(roleCode);
        m.setProjectRole(role);
        m.setAssignmentStatus(status);
        return m;
    }

    private PaymentNotificationEmitter emitterThatAlwaysThrows(List<ProjectMemberEntity> roster) {
        NotificationService notificationService = mock(NotificationService.class);
        doThrow(new RuntimeException("notification backend down"))
                .when(notificationService).create(anyLong(), anyString(), anyString(), anyString());

        ProjectMemberDao projectMemberDao = mock(ProjectMemberDao.class);
        when(projectMemberDao.findByProjectId(PROJECT_ID)).thenReturn(new ArrayList<>(roster));

        PaymentScheduleDao paymentScheduleDao = mock(PaymentScheduleDao.class);
        PaymentScheduleEntity schedule = new PaymentScheduleEntity();
        schedule.setId(SCHEDULE_ID);
        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);
        schedule.setProject(project);
        when(paymentScheduleDao.findById(SCHEDULE_ID)).thenReturn(Optional.of(schedule));

        return new PaymentNotificationEmitter(notificationService, projectMemberDao, paymentScheduleDao);
    }

    // Feature: FOR-06-07-client-payments, Property 9 BestEffortEmission
    // For any recipient roster, every one of the six triggers completes normally even though every
    // NotificationService.create throws — the emitter swallows, so the committed transition is never
    // rolled back (R9.1, R9.5).
    // Validates: Requirements 9.1, 9.5
    @Property(tries = 200)
    void aThrowingNotificationServiceNeverPropagatesOutOfAnyTrigger(
            @ForAll("rosters") List<ProjectMemberEntity> roster) {
        PaymentNotificationEmitter emitter = emitterThatAlwaysThrows(roster);

        assertThatCode(() -> {
            emitter.onPaymentReceived(new PaymentReceivedEvent(SCHEDULE_ID, PROJECT_ID));
            emitter.onTranchePaid(new TranchePaidEvent(100L, SCHEDULE_ID, PROJECT_ID));
            emitter.onTrancheOverdue(new TrancheOverdueEvent(100L, SCHEDULE_ID));
            emitter.onAmountMismatch(new AmountMismatchEvent(SCHEDULE_ID, PROJECT_ID));
            emitter.onSchedulePublished(new SchedulePublishedEvent(SCHEDULE_ID, PROJECT_ID));
            emitter.onOnlineFailed(new PaymentTransactionService.OnlineFailedEvent(
                    500L, SCHEDULE_ID, TransactionStatus.FAILED));
        }).doesNotThrowAnyException();
    }
}
