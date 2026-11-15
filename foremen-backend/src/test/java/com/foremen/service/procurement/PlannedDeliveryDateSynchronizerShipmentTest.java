package com.foremen.service.procurement;

import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.ShipmentLineDao;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.dao.model.ShipmentEntity;
import com.foremen.dao.model.ShipmentLineEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FOR-11-04 task 3.6: synchronizer precedence, fallback and terminal positions (R11.1, R11.2, R26.3). */
class PlannedDeliveryDateSynchronizerShipmentTest {

    private static final LocalDate REQUEST_DATE = LocalDate.of(2030, 1, 10);
    private static final LocalDate CURRENT = LocalDate.of(2030, 1, 20);

    private PurchasePositionDao positionDao;
    private ShipmentLineDao lineDao;
    private PlannedDeliveryDateSynchronizer sync;

    @BeforeEach
    void setUp() {
        positionDao = mock(PurchasePositionDao.class);
        lineDao = mock(ShipmentLineDao.class);
        sync = new PlannedDeliveryDateSynchronizer(positionDao, lineDao);
    }

    private PurchasePositionEntity position(PositionStatus status, boolean withRequest) {
        PurchasePositionEntity p = new PurchasePositionEntity();
        p.setId(5L);
        p.setStatus(status);
        p.setPlannedDeliveryDate(CURRENT);
        if (withRequest) {
            PurchaseRequestEntity r = new PurchaseRequestEntity();
            r.setId(9L);
            r.setExpectedDeliveryDate(REQUEST_DATE);
            p.setPurchaseRequest(r);
        }
        when(positionDao.findById(5L)).thenReturn(Optional.of(p));
        return p;
    }

    private ShipmentLineEntity line(LocalDate eta) {
        ShipmentEntity s = new ShipmentEntity();
        s.setEtaDate(eta);
        ShipmentLineEntity l = new ShipmentLineEntity();
        l.setShipment(s);
        return l;
    }

    @Test
    void latestActiveEtaWins() {
        PurchasePositionEntity p = position(PositionStatus.IN_DELIVERY, true);
        when(lineDao.findActiveByPositionIds(any())).thenReturn(List.of(
                line(LocalDate.of(2030, 2, 1)), line(LocalDate.of(2030, 3, 1)), line(null)));

        sync.onShipmentChanged(5L);

        assertThat(p.getPlannedDeliveryDate()).isEqualTo(LocalDate.of(2030, 3, 1));
    }

    @Test
    void noEtaFallsBackToRequestDate() {
        PurchasePositionEntity p = position(PositionStatus.IN_DELIVERY, true);
        when(lineDao.findActiveByPositionIds(any())).thenReturn(List.of(line(null)));

        sync.onShipmentChanged(5L);

        assertThat(p.getPlannedDeliveryDate()).isEqualTo(REQUEST_DATE);
    }

    @Test
    void noEtaWithoutRequestClearsDate() {
        PurchasePositionEntity p = position(PositionStatus.IN_DELIVERY, false);
        when(lineDao.findActiveByPositionIds(any())).thenReturn(List.of());

        sync.onShipmentChanged(5L);

        assertThat(p.getPlannedDeliveryDate()).isNull();
    }

    @Test
    void deliveredShipmentKeepsCurrentValue() {
        PurchasePositionEntity p = position(PositionStatus.PARTIALLY_RECEIVED, true);
        when(lineDao.findActiveByPositionIds(any())).thenReturn(List.of());
        when(lineDao.existsDeliveredByPositionId(5L)).thenReturn(true);

        sync.onShipmentChanged(5L);

        assertThat(p.getPlannedDeliveryDate()).isEqualTo(CURRENT);
    }

    @Test
    void terminalPositionKeepsValueOnShipmentChange() {
        PurchasePositionEntity p = position(PositionStatus.RECEIVED, true);

        sync.onShipmentChanged(5L);

        assertThat(p.getPlannedDeliveryDate()).isEqualTo(CURRENT);
    }

    @Test
    void requestDateChangeSkipsPositionWithActiveEta() {
        PurchasePositionEntity withEta = position(PositionStatus.IN_DELIVERY, true);
        PurchasePositionEntity other = new PurchasePositionEntity();
        other.setId(6L);
        other.setStatus(PositionStatus.SUPPLIER_PAID);
        PurchaseRequestEntity request = withEta.getPurchaseRequest();
        when(positionDao.findByPurchaseRequestId(9L)).thenReturn(List.of(withEta, other));
        when(lineDao.existsActiveWithEta(5L)).thenReturn(true);
        when(lineDao.existsActiveWithEta(6L)).thenReturn(false);

        sync.onRequestDateChanged(request);

        assertThat(withEta.getPlannedDeliveryDate()).isEqualTo(CURRENT);
        assertThat(other.getPlannedDeliveryDate()).isEqualTo(REQUEST_DATE);
        verify(lineDao).existsActiveWithEta(6L);
    }

    @Test
    void requestDateChangeStillSkipsTerminalPositions() {
        PurchasePositionEntity p = position(PositionStatus.CANCELLED, true);
        when(positionDao.findByPurchaseRequestId(9L)).thenReturn(List.of(p));

        sync.onRequestDateChanged(p.getPurchaseRequest());

        assertThat(p.getPlannedDeliveryDate()).isEqualTo(CURRENT);
    }
}
