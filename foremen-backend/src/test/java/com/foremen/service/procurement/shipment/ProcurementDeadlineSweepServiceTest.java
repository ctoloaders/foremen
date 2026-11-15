package com.foremen.service.procurement.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;

import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.ShipmentDao;
import com.foremen.dao.ShipmentLineDao;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.ShipmentEntity;
import com.foremen.dao.model.ShipmentLineEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.service.procurement.schedule.MaterialScheduleRiskService;
import com.foremen.service.procurement.schedule.MaterialScheduleRiskService.ProjectCategory;

/** FOR-11-04 task 3.14: {@link ProcurementDeadlineSweepService} (R14). */
class ProcurementDeadlineSweepServiceTest {

    // 2025-03-10 12:00 UTC: today in Warsaw is 2025-03-10.
    private static final Instant NOW = Instant.parse("2025-03-10T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2025, 3, 10);

    private final PurchasePositionDao positionDao = mock(PurchasePositionDao.class);
    private final ShipmentDao shipmentDao = mock(ShipmentDao.class);
    private final ShipmentLineDao lineDao = mock(ShipmentLineDao.class);
    private final MaterialScheduleRiskService risk = mock(MaterialScheduleRiskService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

    private final ProcurementDeadlineSweepService service = new ProcurementDeadlineSweepService(
            positionDao, shipmentDao, lineDao, risk, publisher, Clock.fixed(NOW, ZoneOffset.UTC));

    private static ProjectEntity project(long id) {
        ProjectEntity p = new ProjectEntity();
        p.setId(id);
        return p;
    }

    private static PurchasePositionEntity position(long id, LocalDate orderBy, LocalDate notifiedFor) {
        PurchasePositionEntity p = new PurchasePositionEntity();
        p.setId(id);
        p.setProject(project(10));
        p.setStatus(PositionStatus.PLANNED);
        p.setOrderByDate(orderBy);
        p.setOrderByNotifiedFor(notifiedFor);
        return p;
    }

    private static ShipmentEntity shipment(long id, LocalDate eta) {
        ShipmentEntity s = new ShipmentEntity();
        s.setId(id);
        s.setProject(project(10));
        s.setEtaDate(eta);
        return s;
    }

    private ShipmentEntity stageDowntimeShipment(long id, LocalDate eta, LocalDate worksStart) {
        ShipmentEntity s = shipment(id, eta);
        PurchasePositionEntity p = position(id * 100, null, null);
        p.setStatus(PositionStatus.IN_DELIVERY);
        WorkCategoryEntity cat = new WorkCategoryEntity();
        cat.setId(7L);
        p.setWorkCategory(cat);
        ShipmentLineEntity line = new ShipmentLineEntity();
        line.setPosition(p);
        when(lineDao.findByShipmentId(id)).thenReturn(List.of(line));
        when(risk.worksStarts(any())).thenReturn(Map.of(new ProjectCategory(10L, 7L), worksStart));
        return s;
    }

    @Test
    void orderByCandidateIsStampedAndEventPublishedAfterStaging() {
        PurchasePositionEntity p = position(1, LocalDate.of(2025, 3, 8), null);
        when(positionDao.findOrderByMissedCandidates(TODAY)).thenReturn(List.of(p));

        service.sweep();

        assertThat(p.getOrderByNotifiedFor()).isEqualTo(LocalDate.of(2025, 3, 8));
        verify(positionDao).save(p);
        verify(publisher).publishEvent(new PositionOrderByMissedEvent(1L, 10L));
    }

    @Test
    void downtimeRiskStampsMarkerAndPublishesEvent() {
        ShipmentEntity s = stageDowntimeShipment(5, LocalDate.of(2025, 3, 20), LocalDate.of(2025, 3, 15));
        when(shipmentDao.findActiveWithEtaForProjectsWithSchedule()).thenReturn(List.of(s));

        service.sweep();

        assertThat(s.getDowntimeNotifiedFor()).isEqualTo(LocalDate.of(2025, 3, 20));
        verify(publisher).publishEvent(new ShipmentDowntimeRiskEvent(5L, 10L, LocalDate.of(2025, 3, 20), null));
    }

    @Test
    void shipmentWithoutRiskOrAlreadyNotifiedEmitsNothing() {
        ShipmentEntity safe = stageDowntimeShipment(5, LocalDate.of(2025, 3, 10), LocalDate.of(2025, 3, 15));
        ShipmentEntity notified = shipment(6, LocalDate.of(2025, 3, 20));
        notified.setDowntimeNotifiedFor(LocalDate.of(2025, 3, 20));
        when(shipmentDao.findActiveWithEtaForProjectsWithSchedule()).thenReturn(List.of(safe, notified));

        service.sweep();

        verify(publisher, org.mockito.Mockito.never()).publishEvent(any(Object.class));
        assertThat(safe.getDowntimeNotifiedFor()).isNull();
    }

    @Test
    void secondRunEmitsNothing() {
        // Stateful stub: candidates are re-filtered by the marker like the real query does.
        PurchasePositionEntity p = position(1, LocalDate.of(2025, 3, 8), null);
        when(positionDao.findOrderByMissedCandidates(TODAY)).thenAnswer(inv ->
                p.getOrderByDate().equals(p.getOrderByNotifiedFor()) ? List.of() : List.of(p));
        ShipmentEntity s = stageDowntimeShipment(5, LocalDate.of(2025, 3, 20), LocalDate.of(2025, 3, 15));
        when(shipmentDao.findActiveWithEtaForProjectsWithSchedule()).thenReturn(List.of(s));

        service.sweep();
        service.sweep();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(publisher, org.mockito.Mockito.times(2)).publishEvent(captor.capture());
    }

    @Test
    void failureOnOneItemDoesNotStopTheRun() {
        PurchasePositionEntity bad = position(1, LocalDate.of(2025, 3, 8), null);
        PurchasePositionEntity good = position(2, LocalDate.of(2025, 3, 9), null);
        when(positionDao.findOrderByMissedCandidates(TODAY)).thenReturn(List.of(bad, good));
        when(positionDao.save(bad)).thenThrow(new IllegalStateException("boom"));

        service.sweep();

        verify(publisher).publishEvent(new PositionOrderByMissedEvent(2L, 10L));
        verify(publisher, org.mockito.Mockito.never()).publishEvent(new PositionOrderByMissedEvent(1L, 10L));
    }

    @Test
    void scheduledTriggerUsesDeadlineSweepCron() throws Exception {
        Method m = ProcurementDeadlineSweepService.class.getMethod("sweep");
        Scheduled scheduled = m.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("${foremen.procurement.deadline-sweep.cron:-}");
        assertThat(scheduled.zone()).isEqualTo("Europe/Warsaw");
    }
}
