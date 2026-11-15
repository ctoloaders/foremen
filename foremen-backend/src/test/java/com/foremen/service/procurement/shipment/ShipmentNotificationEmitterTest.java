package com.foremen.service.procurement.shipment;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.offer.NotificationService;

/** FOR-11-04 task 3.11 - unit tests for {@link ShipmentNotificationEmitter} (R15). */
class ShipmentNotificationEmitterTest {

    private static final String MGR_LINK = "/deliveries/shipments?shipment=7";
    private static final String FM_LINK = "/deliveries/upcoming?shipment=7";

    private final NotificationService notifications = mock(NotificationService.class);
    private final ProjectMemberDao memberDao = mock(ProjectMemberDao.class);
    private final ShipmentNotificationEmitter emitter = new ShipmentNotificationEmitter(notifications, memberDao);

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

    private void members() {
        when(memberDao.findByProjectIdWithUserAndRole(10L)).thenReturn(List.of(
                member(1L, "MANAGER", AssignmentStatus.ACTIVE),
                member(2L, "FOREMAN", AssignmentStatus.ACTIVE),
                member(2L, "FOREMAN", AssignmentStatus.ACTIVE),
                member(1L, "FOREMAN", AssignmentStatus.ACTIVE), // both roles: manager link wins
                member(3L, "FOREMAN", AssignmentStatus.INACTIVE),
                member(4L, "MANAGER", AssignmentStatus.ACTIVE),
                member(5L, "FOREMAN", AssignmentStatus.ACTIVE),
                member(6L, "BUYER", AssignmentStatus.ACTIVE)));
    }

    @Test
    void createdNotifiesManagersAndForemenWithRoleLinksMinusActorAndDuplicates() {
        members();
        emitter.onShipmentCreated(new ShipmentCreatedEvent(7L, 10L, List.of(1L), 4L));

        verify(notifications).create(1L, "notification.shipment.shipped", null, MGR_LINK);
        verify(notifications).create(2L, "notification.shipment.shipped", null, FM_LINK);
        verify(notifications).create(5L, "notification.shipment.shipped", null, FM_LINK);
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void actorWhoIsManagerAndForemanIsNotNotifiedAtAll() {
        members();
        emitter.onShipmentCreated(new ShipmentCreatedEvent(7L, 10L, List.of(), 1L));

        verify(notifications).create(4L, "notification.shipment.shipped", null, MGR_LINK);
        verify(notifications).create(2L, "notification.shipment.shipped", null, FM_LINK);
        verify(notifications).create(5L, "notification.shipment.shipped", null, FM_LINK);
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void delayedRequiresAtLeastOneDay() {
        members();
        LocalDate d = LocalDate.of(2026, 3, 10);

        emitter.onEtaDelayed(new ShipmentEtaDelayedEvent(7L, 10L, d, d, null));
        emitter.onEtaDelayed(new ShipmentEtaDelayedEvent(7L, 10L, d, d.minusDays(2), null));
        verifyNoInteractions(notifications);

        emitter.onEtaDelayed(new ShipmentEtaDelayedEvent(7L, 10L, d, d.plusDays(1), null));
        verify(notifications).create(1L, "notification.shipment.delayed", null, MGR_LINK);
        verify(notifications).create(2L, "notification.shipment.delayed", null, FM_LINK);
    }

    @Test
    void downtimeRiskUsesSameRecipientsAndLinks() {
        members();
        emitter.onDowntimeRisk(new ShipmentDowntimeRiskEvent(7L, 10L, LocalDate.of(2026, 3, 10), null));

        verify(notifications).create(1L, "notification.shipment.downtimeRisk", null, MGR_LINK);
        verify(notifications).create(4L, "notification.shipment.downtimeRisk", null, MGR_LINK);
        verify(notifications).create(2L, "notification.shipment.downtimeRisk", null, FM_LINK);
        verify(notifications).create(5L, "notification.shipment.downtimeRisk", null, FM_LINK);
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void orderByNotifiesActiveManagersOnly() {
        members();
        emitter.onOrderByMissed(new PositionOrderByMissedEvent(100L, 10L));

        verify(notifications).create(1L, "notification.position.orderBy", null, "/deliveries?project=10");
        verify(notifications).create(4L, "notification.position.orderBy", null, "/deliveries?project=10");
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void failuresAreIsolated() {
        members();
        doThrow(new RuntimeException("boom")).when(notifications)
                .create(1L, "notification.shipment.shipped", null, MGR_LINK);
        emitter.onShipmentCreated(new ShipmentCreatedEvent(7L, 10L, List.of(), null));
        verify(notifications).create(2L, "notification.shipment.shipped", null, FM_LINK);

        when(memberDao.findByProjectIdWithUserAndRole(11L)).thenThrow(new RuntimeException("db down"));
        emitter.onShipmentCreated(new ShipmentCreatedEvent(8L, 11L, List.of(), null));
        emitter.onShipmentCreated(null);
        emitter.onEtaDelayed(null);
        emitter.onDowntimeRisk(null);
        emitter.onOrderByMissed(null);
    }
}
