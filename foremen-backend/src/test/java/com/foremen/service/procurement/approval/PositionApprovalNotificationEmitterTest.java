package com.foremen.service.procurement.approval;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.offer.NotificationService;
import com.foremen.service.procurement.approval.PositionDecisionEvent.Outcome;

/** FOR-11-02 task 6.1 - unit tests for {@link PositionApprovalNotificationEmitter} (R15.1-R15.5). */
class PositionApprovalNotificationEmitterTest {

    private static final String TAB = "/projects/10/positionApprovals";
    private static final String POS = TAB + "?position=3";

    private final NotificationService notifications = mock(NotificationService.class);
    private final ProjectMemberDao memberDao = mock(ProjectMemberDao.class);
    private final UserDao userDao = mock(UserDao.class);
    private final PurchasePositionDao positionDao = mock(PurchasePositionDao.class);
    private final PositionApprovalNotificationEmitter emitter =
            new PositionApprovalNotificationEmitter(notifications, memberDao, userDao, positionDao);

    private static UserEntity user(long id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setActive(true);
        return u;
    }

    private static ProjectMemberEntity member(long userId, String role, AssignmentStatus status) {
        ProjectMemberEntity m = new ProjectMemberEntity();
        m.setUser(user(userId));
        RoleEntity r = new RoleEntity();
        r.setCode(role);
        m.setProjectRole(r);
        m.setAssignmentStatus(status);
        return m;
    }

    private void members() {
        when(memberDao.findByProjectIdWithUserAndRole(10L)).thenReturn(List.of(
                member(1L, "CLIENT", AssignmentStatus.ACTIVE),
                member(1L, "CLIENT", AssignmentStatus.ACTIVE),      // duplicate row
                member(2L, "CLIENT", AssignmentStatus.INACTIVE),    // skipped
                member(3L, "MANAGER", AssignmentStatus.ACTIVE),
                member(4L, "MANAGER", AssignmentStatus.ACTIVE),
                member(5L, "FOREMAN", AssignmentStatus.ACTIVE)));
    }

    @Test
    void sentForDecisionNotifiesActiveClientsOnce() {
        members();
        emitter.onSentForDecision(new PositionsSentForDecisionEvent(10L, List.of(3L, 4L)));
        verify(notifications, times(1)).create(1L, "notification.position.decisionRequested", null, TAB);
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void analogProposedNotifiesClientsWithPositionLink() {
        members();
        emitter.onAnalogProposed(new PositionAnalogProposedEvent(10L, 3L));
        verify(notifications).create(1L, "notification.position.analogProposed", null, POS);
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void acceptedAndRejectedNotifyManagersExceptActor() {
        members();
        emitter.onDecision(new PositionDecisionEvent(10L, 3L, Outcome.ACCEPTED, false, 3L));
        emitter.onDecision(new PositionDecisionEvent(10L, 3L, Outcome.REJECTED, false, 99L));
        verify(notifications, never()).create(3L, "notification.position.accepted", null, POS);
        verify(notifications).create(4L, "notification.position.accepted", null, POS);
        verify(notifications).create(3L, "notification.position.rejected", null, POS);
        verify(notifications).create(4L, "notification.position.rejected", null, POS);
    }

    @Test
    void onBehalfDecisionNotifiesNobody() {
        members();
        emitter.onDecision(new PositionDecisionEvent(10L, 3L, Outcome.ACCEPTED, true, 3L));
        emitter.onDecision(new PositionDecisionEvent(10L, 3L, Outcome.CLIENT_OWN, true, 3L));
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void clientOwnNotifiesManagersAndAssignedBuyerDeduplicated() {
        members();
        when(positionDao.findAssignedBuyerId(3L)).thenReturn(4L);   // also a manager -> once
        emitter.onDecision(new PositionDecisionEvent(10L, 3L, Outcome.CLIENT_OWN, false, 1L));
        verify(notifications, times(1)).create(3L, "notification.position.clientOwn", null, POS);
        verify(notifications, times(1)).create(4L, "notification.position.clientOwn", null, POS);
        verifyNoMoreInteractions(notifications);
        verify(userDao, never()).findActiveByRoleCode(anyString());
    }

    @Test
    void clientOwnFallsBackToAllActiveBuyers() {
        members();
        when(positionDao.findAssignedBuyerId(3L)).thenReturn(null);
        when(userDao.findActiveByRoleCode("BUYER")).thenReturn(List.of(user(7L), user(8L)));
        emitter.onDecision(new PositionDecisionEvent(10L, 3L, Outcome.CLIENT_OWN, false, 1L));
        verify(notifications).create(7L, "notification.position.clientOwn", null, POS);
        verify(notifications).create(8L, "notification.position.clientOwn", null, POS);
        verify(notifications).create(3L, "notification.position.clientOwn", null, POS);
    }

    @Test
    void suggestionNotifiesManagers() {
        members();
        emitter.onSuggestion(new PositionSuggestionEvent(10L, 3L));
        verify(notifications).create(3L, "notification.position.suggestionReceived", null, POS);
        verify(notifications).create(4L, "notification.position.suggestionReceived", null, POS);
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void failureOfOneRecipientDoesNotStopOthersOrPropagate() {
        members();
        doThrow(new RuntimeException("boom")).when(notifications)
                .create(3L, "notification.position.suggestionReceived", null, POS);
        emitter.onSuggestion(new PositionSuggestionEvent(10L, 3L));
        verify(notifications).create(4L, "notification.position.suggestionReceived", null, POS);
    }

    @Test
    void recipientResolutionFailureIsSwallowed() {
        when(memberDao.findByProjectIdWithUserAndRole(anyLong())).thenThrow(new RuntimeException("db"));
        emitter.onSentForDecision(new PositionsSentForDecisionEvent(10L, List.of(3L)));
        verify(notifications, never()).create(anyLong(), anyString(), any(), anyString());
    }
}
