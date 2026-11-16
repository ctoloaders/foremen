package com.foremen.service.procurement;

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
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.foremen.dao.ClientProformaDao;
import com.foremen.dao.ExternalOrderDao;
import com.foremen.service.procurement.externalorder.ExternalOrderPaymentLinkChangedEvent;
import com.foremen.service.procurement.position.PositionAddedByBuyerEvent;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.bank.detect.LedgerMatchProposedEvent;
import com.foremen.service.offer.NotificationService;
import com.foremen.service.procurement.externalorder.ExternalOrderPaidEvent;
import com.foremen.service.procurement.externalorder.ExternalOrderSentEvent;
import com.foremen.service.procurement.proforma.ProformaPaidEvent;

/** FOR-11-03 task 6.1 - unit tests for {@link ProformaOrderNotificationEmitter} (R19.1-R19.5). */
class ProformaOrderNotificationEmitterTest {

    private static final String ORDER_LINK = "/deliveries/external-orders?order=7";
    private static final String PAID_LINK = "/deliveries/shipments?new=1&order=7";

    private final NotificationService notifications = mock(NotificationService.class);
    private final ProjectMemberDao memberDao = mock(ProjectMemberDao.class);
    private final UserDao userDao = mock(UserDao.class);
    private final PurchasePositionDao positionDao = mock(PurchasePositionDao.class);
    private final ExternalOrderDao orderDao = mock(ExternalOrderDao.class);
    private final ClientProformaDao proformaDao = mock(ClientProformaDao.class);
    private final ProformaOrderNotificationEmitter emitter = new ProformaOrderNotificationEmitter(
            notifications, memberDao, userDao, positionDao, orderDao, proformaDao);

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

    @Test
    void proformaPaidNotifiesManagersAndAssignedBuyersOnceMinusActor() {
        when(memberDao.findByProjectIdWithUserAndRole(10L)).thenReturn(List.of(
                member(1L, "MANAGER", AssignmentStatus.ACTIVE),
                member(1L, "MANAGER", AssignmentStatus.ACTIVE),
                member(2L, "MANAGER", AssignmentStatus.INACTIVE),
                member(3L, "MANAGER", AssignmentStatus.ACTIVE),
                member(4L, "FOREMAN", AssignmentStatus.ACTIVE)));
        when(proformaDao.findClientPaymentId(5L)).thenReturn(5L);
        when(positionDao.findAssignedBuyerId(100L)).thenReturn(8L);
        when(positionDao.findAssignedBuyerId(101L)).thenReturn(8L);
        when(positionDao.findAssignedBuyerId(102L)).thenReturn(9L);

        emitter.onProformaPaid(new ProformaPaidEvent(5L, 10L, List.of(100L, 101L, 102L), 3L));

        String paidLink = "/projects/10/clientPayments?payment=5";
        String buyerLink = "/deliveries/external-orders?project=10&proforma=5";
        verify(notifications, times(1)).create(1L, "notification.proforma.paid", null, paidLink);
        verify(notifications, times(1)).create(8L, "notification.order.releasedToBuyer", null, buyerLink);
        verify(notifications, times(1)).create(9L, "notification.order.releasedToBuyer", null, buyerLink);
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void proformaPaidFallsBackToActiveBuyersAndSkipsNullActor() {
        when(memberDao.findByProjectIdWithUserAndRole(10L)).thenReturn(List.of());
        when(userDao.findActiveByRoleCode("BUYER")).thenReturn(List.of(user(20L), user(21L)));
        when(positionDao.findAssignedBuyerId(100L)).thenReturn(null);

        emitter.onProformaPaid(new ProformaPaidEvent(5L, 10L, List.of(100L), null));

        String buyerLink = "/deliveries/external-orders?project=10&proforma=5";
        verify(notifications).create(20L, "notification.order.releasedToBuyer", null, buyerLink);
        verify(notifications).create(21L, "notification.order.releasedToBuyer", null, buyerLink);
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void orderSentNotifiesFinanciersExceptActor() {
        when(userDao.findActiveByRoleCode("FINANCIER")).thenReturn(List.of(user(30L), user(31L)));

        emitter.onOrderSent(new ExternalOrderSentEvent(7L, 10L, 30L));

        verify(notifications, never()).create(30L, "notification.externalOrder.sentForPayment", null, ORDER_LINK);
        verify(notifications).create(31L, "notification.externalOrder.sentForPayment", null, ORDER_LINK);
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void orderPaidPrefersAssignedBuyerThenCreatorThenBuyers() {
        when(orderDao.findAssignedBuyerId(7L)).thenReturn(40L);
        emitter.onOrderPaid(new ExternalOrderPaidEvent(7L, 10L, null));
        verify(notifications).create(40L, "notification.externalOrder.paid", null, PAID_LINK);
        verifyNoMoreInteractions(notifications);

        when(orderDao.findAssignedBuyerId(7L)).thenReturn(null);
        when(orderDao.findCreatedBy(7L)).thenReturn("creator@example.com");
        when(userDao.findByEmailIgnoreCase("creator@example.com")).thenReturn(Optional.of(user(41L)));
        emitter.onOrderPaid(new ExternalOrderPaidEvent(7L, 10L, null));
        verify(notifications).create(41L, "notification.externalOrder.paid", null, PAID_LINK);

        when(orderDao.findCreatedBy(7L)).thenReturn(null);
        when(userDao.findActiveByRoleCode("BUYER")).thenReturn(List.of(user(42L), user(43L)));
        emitter.onOrderPaid(new ExternalOrderPaidEvent(7L, 10L, 43L));
        verify(notifications).create(42L, "notification.externalOrder.paid", null, PAID_LINK);
        verify(notifications, never()).create(43L, "notification.externalOrder.paid", null, PAID_LINK);
    }

    @Test
    void orderPaidLinksToTheNewShipmentDialog() {
        when(orderDao.findAssignedBuyerId(7L)).thenReturn(40L);

        emitter.onOrderPaid(new ExternalOrderPaidEvent(7L, 10L, null));

        verify(notifications).create(40L, "notification.externalOrder.paid", null,
                "/deliveries/shipments?new=1&order=7");
    }

    @Test
    void paymentLinkChangedNotifiesFinanciersMinusActor() {
        when(userDao.findActiveByRoleCode("FINANCIER")).thenReturn(List.of(user(30L), user(31L)));

        emitter.onPaymentLinkChanged(new ExternalOrderPaymentLinkChangedEvent(7L, 10L, 30L));

        verify(notifications).create(31L, "notification.externalOrder.paymentLinkAdded", null, ORDER_LINK);
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void positionAddedByBuyerNotifiesClientsAndManagersWithSeparateLinksMinusActor() {
        when(memberDao.findByProjectIdWithUserAndRole(10L)).thenReturn(List.of(
                member(1L, "CLIENT", AssignmentStatus.ACTIVE),
                member(2L, "MANAGER", AssignmentStatus.ACTIVE),
                member(3L, "MANAGER", AssignmentStatus.ACTIVE),
                member(4L, "CLIENT", AssignmentStatus.INACTIVE),
                member(5L, "FOREMAN", AssignmentStatus.ACTIVE)));

        emitter.onPositionAddedByBuyer(new PositionAddedByBuyerEvent(55L, 10L, 3L));

        String type = "notification.position.addedByBuyer";
        verify(notifications).create(1L, type, null, "/projects/10/positionApprovals?position=55");
        verify(notifications).create(2L, type, null, "/deliveries?project=10&position=55");
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void proformaPaidLinkUsesPaymentIdWhenKnown() {
        when(memberDao.findByProjectIdWithUserAndRole(10L)).thenReturn(List.of(
                member(1L, "MANAGER", AssignmentStatus.ACTIVE)));
        when(proformaDao.findClientPaymentId(5L)).thenReturn(77L);
        when(positionDao.findAssignedBuyerId(100L)).thenReturn(8L);
        // a buyer is resolved too; only the manager notification is asserted here

        emitter.onProformaPaid(new ProformaPaidEvent(5L, 10L, List.of(100L), null));

        verify(notifications).create(1L, "notification.proforma.paid", null, "/projects/10/clientPayments?payment=77");
    }

    @Test
    void matchProposedNotifiesFinanciersWithLedgerLink() {
        when(userDao.findActiveByRoleCode("FINANCIER")).thenReturn(List.of(user(30L)));

        emitter.onLedgerMatchProposed(new LedgerMatchProposedEvent(55L));

        verify(notifications).create(30L, "notification.invoice.matchProposed", null,
                "/company/bank-transactions?transactionId=55");
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void failuresAreSwallowedAndDoNotStarveOtherRecipients() {
        when(userDao.findActiveByRoleCode("FINANCIER")).thenReturn(List.of(user(30L), user(31L)));
        doThrow(new RuntimeException("boom")).when(notifications)
                .create(30L, "notification.invoice.matchProposed", null, "/company/bank-transactions?transactionId=1");

        emitter.onLedgerMatchProposed(new LedgerMatchProposedEvent(1L));

        verify(notifications).create(31L, "notification.invoice.matchProposed", null,
                "/company/bank-transactions?transactionId=1");

        when(userDao.findActiveByRoleCode("FINANCIER")).thenThrow(new RuntimeException("db down"));
        emitter.onOrderSent(new ExternalOrderSentEvent(7L, 10L, null));
        emitter.onLedgerMatchProposed(null);
        emitter.onProformaPaid(null);
        verify(notifications, never()).create(anyLong(), anyString(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(ORDER_LINK));
    }
}
