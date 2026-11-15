package com.foremen.service.procurement.externalorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import com.foremen.dao.ExternalOrderLineDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.model.ExternalOrderEntity;
import com.foremen.dao.model.ExternalOrderLineEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.service.PurchasePositionService;
import com.foremen.service.procurement.position.PositionLifecycleService;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;

import jakarta.persistence.EntityManager;

/** FOR-11-03 task 4.4: R13.2, R13.3, R17.1-17.3, 17.5, 17.6. */
class PositionOrderSyncServiceTest {

    private static final Long ID = 7L;

    private ExternalOrderLineDao lineDao;
    private PurchasePositionDao positionDao;
    private PositionLifecycleService lifecycle;
    private PositionOrderSyncService service;
    private PurchasePositionEntity position;

    @BeforeEach
    void setUp() {
        lineDao = mock(ExternalOrderLineDao.class);
        positionDao = mock(PurchasePositionDao.class);
        PurchasePositionService positionService = mock(PurchasePositionService.class);
        lifecycle = mock(PositionLifecycleService.class);
        when(positionService.getDao()).thenReturn(positionDao);
        when(positionService.getEntityManager()).thenReturn(mock(EntityManager.class));
        when(positionService.serializeEntity(any())).thenReturn("{}");
        when(positionDao.save(any(PurchasePositionEntity.class))).thenAnswer(i -> i.getArgument(0));

        position = new PurchasePositionEntity();
        position.setSourcingPath(SourcingPath.AGREED);
        position.setQuantity(new BigDecimal("10.000"));
        position.setStatus(PositionStatus.WITH_BUYER);
        when(positionDao.findById(ID)).thenAnswer(i -> Optional.of(position));
        doAnswer(i -> {
            position.setStatus(i.getArgument(1));
            return null;
        }).when(lifecycle).transition(eq(ID), any(PositionStatus.class), eq(Actor.SYSTEM));

        service = new PositionOrderSyncService(lineDao, positionService, lifecycle);
    }

    private static ExternalOrderLineEntity line(ExternalOrderStatus status, String qty, String price, String share) {
        ExternalOrderEntity order = new ExternalOrderEntity();
        order.setStatus(status);
        ExternalOrderLineEntity l = new ExternalOrderLineEntity();
        l.setExternalOrder(order);
        l.setQuantity(new BigDecimal(qty));
        l.setUnitPurchaseNet(new BigDecimal(price));
        l.setDeliveryCostShare(new BigDecimal(share));
        return l;
    }

    private void lines(ExternalOrderLineEntity... ls) {
        when(lineDao.findCommittedByPositionId(ID)).thenReturn(List.of(ls));
    }

    @Test
    void fullyCoveredSentOrderMovesWithBuyerToOrderedAndRecomputesFields() {
        lines(line(ExternalOrderStatus.SENT_FOR_PAYMENT, "4", "10.00", "1.00"),
                line(ExternalOrderStatus.SENT_FOR_PAYMENT, "6", "20.00", "2.50"));

        service.sync(ID);

        assertThat(position.getOrderedQuantity()).isEqualByComparingTo("10");
        assertThat(position.getActualPurchasePrice()).isEqualByComparingTo("16.00"); // (40+120)/10
        assertThat(position.getDeliveryCostShare()).isEqualByComparingTo("3.50");
        assertThat(position.getStatus()).isEqualTo(PositionStatus.ORDERED);
        verify(lifecycle, times(1)).transition(ID, PositionStatus.ORDERED, Actor.SYSTEM);
    }

    @Test
    void fullyCoveredPaidOrderWalksOneStepAtATimeToSupplierPaid() {
        lines(line(ExternalOrderStatus.PAID, "10", "5.00", "0.00"));

        service.sync(ID);

        InOrder order = Mockito.inOrder(lifecycle);
        order.verify(lifecycle).transition(ID, PositionStatus.ORDERED, Actor.SYSTEM);
        order.verify(lifecycle).transition(ID, PositionStatus.SUPPLIER_PAID, Actor.SYSTEM);
        assertThat(position.getStatus()).isEqualTo(PositionStatus.SUPPLIER_PAID);
    }

    @Test
    void secondRunIsIdempotent() {
        lines(line(ExternalOrderStatus.PAID, "10", "5.00", "0.00"));
        service.sync(ID);
        Mockito.clearInvocations(lifecycle, positionDao);

        service.sync(ID);

        verify(lifecycle, never()).transition(any(), any(), any());
        verify(positionDao, never()).save(any(PurchasePositionEntity.class));
    }

    @Test
    void partialCoverageKeepsWithBuyerButStoresOrderedQuantity() {
        lines(line(ExternalOrderStatus.SENT_FOR_PAYMENT, "3", "10.00", "0.00"));

        service.sync(ID);

        assertThat(position.getOrderedQuantity()).isEqualByComparingTo("3");
        assertThat(position.getStatus()).isEqualTo(PositionStatus.WITH_BUYER);
        verify(lifecycle, never()).transition(any(), any(), any());
    }

    @Test
    void returnedOrderRevertsOrderedToWithBuyerAndClearsFields() {
        position.setStatus(PositionStatus.ORDERED);
        position.setOrderedQuantity(new BigDecimal("10.000"));
        position.setActualPurchasePrice(new BigDecimal("5.00"));
        position.setDeliveryCostShare(new BigDecimal("1.00"));
        lines();

        service.sync(ID);

        assertThat(position.getStatus()).isEqualTo(PositionStatus.WITH_BUYER);
        assertThat(position.getOrderedQuantity()).isEqualByComparingTo("0");
        assertThat(position.getActualPurchasePrice()).isNull();
        assertThat(position.getDeliveryCostShare()).isEqualByComparingTo("0");
    }

    @Test
    void supplierPaidStepsBackToOrderedWhenAnOrderIsNoLongerPaid() {
        position.setStatus(PositionStatus.SUPPLIER_PAID);
        lines(line(ExternalOrderStatus.PAID, "5", "5.00", "0.00"),
                line(ExternalOrderStatus.SENT_FOR_PAYMENT, "5", "5.00", "0.00"));

        service.sync(ID);

        assertThat(position.getStatus()).isEqualTo(PositionStatus.ORDERED);
    }

    @Test
    void inDeliveryAndLaterAreSkippedEntirely() {
        position.setStatus(PositionStatus.IN_DELIVERY);
        position.setOrderedQuantity(new BigDecimal("10.000"));
        lines();

        service.sync(ID);

        assertThat(position.getOrderedQuantity()).isEqualByComparingTo("10");
        verify(lineDao, never()).findCommittedByPositionId(any());
        verify(lifecycle, never()).transition(any(), any(), any());
    }

    @Test
    void unknownPositionIsIgnored() {
        service.sync(999L);

        verify(lifecycle, never()).transition(any(), any(), any());
    }
}
