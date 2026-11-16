package com.foremen.service.procurement.receipt;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

/**
 * FOR-11-05 task 3.5 - unit tests for {@link GoodsReceiptNotificationEmitter}.
 *
 * <p>Whether an event is published at all (no event for a clean ACCEPTED or a void) is decided by the
 * publisher, not by the emitter; that check belongs to the service tests.
 *
 * <p>Validates: Requirements 12, 15.2
 */
class GoodsReceiptNotificationEmitterTest {

    private static final String TYPE = "notification.receipt.discrepancy";
    private static final String LINK = "/deliveries/receipt?position=55";

    private final NotificationService notifications = mock(NotificationService.class);
    private final ProjectMemberDao memberDao = mock(ProjectMemberDao.class);
    private final UserDao userDao = mock(UserDao.class);
    private final GoodsReceiptNotificationEmitter emitter =
            new GoodsReceiptNotificationEmitter(notifications, memberDao, userDao);

    private static ProjectMemberEntity member(long userId, String role, AssignmentStatus status) {
        ProjectMemberEntity m = new ProjectMemberEntity();
        UserEntity u = new UserEntity();
        u.setId(userId);
        m.setUser(u);
        RoleEntity r = new RoleEntity();
        r.setCode(role);
        m.setProjectRole(r);
        m.setAssignmentStatus(status);
        return m;
    }

    private static UserEntity user(long id) {
        UserEntity u = new UserEntity();
        u.setId(id);
        return u;
    }

    private void directory() {
        when(memberDao.findByProjectIdWithUserAndRole(10L)).thenReturn(List.of(
                member(1L, "MANAGER", AssignmentStatus.ACTIVE),
                member(2L, "FOREMAN", AssignmentStatus.ACTIVE),
                member(3L, "MANAGER", AssignmentStatus.INACTIVE),
                member(4L, "MANAGER", AssignmentStatus.ACTIVE),
                member(7L, "MANAGER", AssignmentStatus.ACTIVE)));
        // 7 is both a project manager and a buyer; 8 is a buyer only.
        when(userDao.findActiveByRoleCode("BUYER")).thenReturn(List.of(user(7L), user(8L)));
    }

    private static GoodsReceiptDiscrepancyEvent event(long actor) {
        return new GoodsReceiptDiscrepancyEvent(55L, 10L, 99L, actor);
    }

    @Test
    void notifiesActiveManagersAndBuyersDeduplicatedMinusActorWithLink() {
        directory();

        emitter.onDiscrepancy(event(2L));

        verify(notifications).create(1L, TYPE, null, LINK);
        verify(notifications).create(4L, TYPE, null, LINK);
        verify(notifications).create(7L, TYPE, null, LINK);
        verify(notifications).create(8L, TYPE, null, LINK);
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void actorWhoIsManagerAndBuyerIsExcluded() {
        directory();

        emitter.onDiscrepancy(event(7L));

        verify(notifications).create(1L, TYPE, null, LINK);
        verify(notifications).create(4L, TYPE, null, LINK);
        verify(notifications).create(8L, TYPE, null, LINK);
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void foremenAndInactiveManagersAreNotRecipients() {
        directory();

        emitter.onDiscrepancy(event(1L));

        verify(notifications, never()).create(eq(2L), anyString(), any(), anyString());
        verify(notifications, never()).create(eq(3L), anyString(), any(), anyString());
    }

    @Test
    void nullOrIncompleteEventEmitsNothing() {
        emitter.onDiscrepancy(null);
        emitter.onDiscrepancy(new GoodsReceiptDiscrepancyEvent(null, 10L, 1L, 1L));
        emitter.onDiscrepancy(new GoodsReceiptDiscrepancyEvent(55L, null, 1L, 1L));

        verifyNoInteractions(notifications, memberDao, userDao);
    }

    @Test
    void oneFailingNotificationDoesNotStopTheOthers() {
        directory();
        doThrow(new RuntimeException("boom")).when(notifications).create(eq(1L), anyString(), any(), anyString());

        emitter.onDiscrepancy(event(2L));

        verify(notifications).create(4L, TYPE, null, LINK);
        verify(notifications).create(7L, TYPE, null, LINK);
        verify(notifications).create(8L, TYPE, null, LINK);
    }

    @Test
    void memberLookupFailureIsSwallowedAndNothingIsEmitted() {
        when(memberDao.findByProjectIdWithUserAndRole(anyLong())).thenThrow(new RuntimeException("db down"));

        emitter.onDiscrepancy(event(2L));

        verifyNoInteractions(notifications);
    }

    @Test
    void delegationNotifiesAssigneeWithLinkAndPreviousAssigneeWithoutLink() {
        emitter.onDelegated(new ReceiptDelegatedEvent(1L, 70L, 10L, 21L, 22L, 1L));

        verify(notifications).create(21L, "notification.receipt.delegated", null, "/deliveries/receipt?shipment=70");
        verify(notifications).create(22L, "notification.receipt.delegationRevoked", null, null);
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void firstDelegationHasNoRevokedNotificationAndActorIsExcluded() {
        emitter.onDelegated(new ReceiptDelegatedEvent(1L, 70L, 10L, 21L, null, 21L));

        verifyNoInteractions(notifications);
    }

    @Test
    void revocationNotifiesPreviousAssigneeWithoutLink() {
        emitter.onRevoked(new ReceiptDelegationRevokedEvent(1L, 70L, 10L, 21L, 1L));

        verify(notifications).create(21L, "notification.receipt.delegationRevoked", null, null);
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void firstDelegateEventNotifiesDelegatingManagerAndProjectManagersOnceMinusActor() {
        directory();

        // 1 and 4 are project managers; 5 delegated (not a member); 2 is the delegated worker (actor).
        emitter.onFirstDelegateEvent(new ReceiptFirstDelegateEvent(1L, 70L, 10L, 1L, 2L));

        String type = "notification.receipt.acceptedByDelegate";
        String link = "/deliveries/shipments/70";
        verify(notifications, org.mockito.Mockito.times(1)).create(1L, type, null, link);
        verify(notifications).create(4L, type, null, link);
        verify(notifications).create(7L, type, null, link);
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void delegationEventsNeverThrowOnNullOrFailure() {
        emitter.onDelegated(null);
        emitter.onRevoked(null);
        emitter.onFirstDelegateEvent(null);
        doThrow(new RuntimeException("boom")).when(notifications).create(eq(21L), anyString(), any(), any());

        emitter.onDelegated(new ReceiptDelegatedEvent(1L, 70L, 10L, 21L, 22L, 1L));

        verify(notifications).create(22L, "notification.receipt.delegationRevoked", null, null);
    }

    @Test
    void buyerLookupFailureIsSwallowedAndNothingIsEmitted() {
        when(memberDao.findByProjectIdWithUserAndRole(10L)).thenReturn(List.of());
        when(userDao.findActiveByRoleCode("BUYER")).thenThrow(new RuntimeException("db down"));

        emitter.onDiscrepancy(event(2L));

        verifyNoInteractions(notifications);
    }
}
