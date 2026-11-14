package com.foremen.service.procurement;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.offer.NotificationService;

/** FOR-11-00 task 11.4 — unit tests for {@link ProcurementNotificationEmitter} (R13.1, R13.3, R13.5-R13.7). */
class ProcurementNotificationEmitterTest {

    private static final String LINK = "/deliveries?request=7";

    private final NotificationService notifications = mock(NotificationService.class);
    private final UserDao userDao = mock(UserDao.class);
    private final ProjectMemberDao memberDao = mock(ProjectMemberDao.class);
    private final ProcurementNotificationEmitter emitter =
            new ProcurementNotificationEmitter(notifications, userDao, memberDao);

    private static UserEntity user(long id, boolean active) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setActive(active);
        return u;
    }

    private static ProjectMemberEntity member(long userId, String role, AssignmentStatus status) {
        ProjectMemberEntity m = new ProjectMemberEntity();
        m.setUser(user(userId, true));
        RoleEntity r = new RoleEntity();
        r.setCode(role);
        m.setProjectRole(r);
        m.setAssignmentStatus(status);
        return m;
    }

    @Test
    void assignedNotifiesOnlyTheBuyer() {
        emitter.onAssigned(new PurchaseRequestAssignedEvent(7L, 5L));

        verify(notifications).create(5L, "notification.request.assigned", null, LINK);
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void assignedWithoutBuyerDoesNothing() {
        emitter.onAssigned(new PurchaseRequestAssignedEvent(7L, null));
        verify(notifications, never()).create(anyLong(), anyString(), any(), anyString());
    }

    @Test
    void overdueWithAssignedBuyerNotifiesBuyerAndActiveManagersOnce() {
        when(memberDao.findByProjectId(10L)).thenReturn(List.of(
                member(5L, "MANAGER", AssignmentStatus.ACTIVE),     // same as buyer -> once
                member(6L, "MANAGER", AssignmentStatus.ACTIVE),
                member(8L, "MANAGER", AssignmentStatus.INACTIVE),   // skipped
                member(9L, "FOREMAN", AssignmentStatus.ACTIVE)));   // skipped

        emitter.onOverdue(new PurchaseRequestOverdueEvent(7L, 10L, 5L));

        verify(notifications, times(1)).create(5L, "notification.request.overdue", null, LINK);
        verify(notifications, times(1)).create(6L, "notification.request.overdue", null, LINK);
        verifyNoMoreInteractions(notifications);
        verify(userDao, never()).findByRoleCode(anyString());
    }

    @Test
    void overdueUnassignedNotifiesActiveBuyersAndManagersEachOnce() {
        when(userDao.findByRoleCode("BUYER")).thenReturn(List.of(
                user(1L, true), user(2L, true), user(3L, false)));
        when(memberDao.findByProjectId(10L)).thenReturn(List.of(
                member(2L, "MANAGER", AssignmentStatus.ACTIVE),     // also a buyer -> once
                member(4L, "MANAGER", AssignmentStatus.ACTIVE)));

        emitter.onOverdue(new PurchaseRequestOverdueEvent(7L, 10L, null));

        verify(notifications).create(1L, "notification.request.overdue", null, LINK);
        verify(notifications, times(1)).create(2L, "notification.request.overdue", null, LINK);
        verify(notifications).create(4L, "notification.request.overdue", null, LINK);
        verify(notifications, never()).create(eq(3L), anyString(), any(), anyString());
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void failureForOneRecipientDoesNotStopOthers() {
        when(userDao.findByRoleCode("BUYER")).thenReturn(List.of(user(1L, true), user(2L, true)));
        when(memberDao.findByProjectId(10L)).thenReturn(List.of());
        doThrow(new RuntimeException("boom")).when(notifications)
                .create(eq(1L), anyString(), isNull(), anyString());

        emitter.onOverdue(new PurchaseRequestOverdueEvent(7L, 10L, null));

        verify(notifications).create(2L, "notification.request.overdue", null, LINK);
    }

    @Test
    void assignedFailureIsSwallowed() {
        doThrow(new RuntimeException("boom")).when(notifications)
                .create(eq(5L), anyString(), isNull(), anyString());

        emitter.onAssigned(new PurchaseRequestAssignedEvent(7L, 5L));
    }

    @Test
    void recipientResolutionFailureIsSwallowed() {
        when(userDao.findByRoleCode("BUYER")).thenThrow(new RuntimeException("db down"));

        emitter.onOverdue(new PurchaseRequestOverdueEvent(7L, 10L, null));

        verify(notifications, never()).create(anyLong(), anyString(), any(), anyString());
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
