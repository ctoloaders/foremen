package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.foremen.dao.model.DeliveryMethod;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectScheduleBarEntity;
import com.foremen.dao.model.ProjectScheduleEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.ShipmentEntity;
import com.foremen.dao.model.ShipmentLineEntity;
import com.foremen.dao.model.ShipmentStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.service.procurement.shipment.PositionOrderByMissedEvent;
import com.foremen.service.procurement.shipment.ProcurementDeadlineSweepService;
import com.foremen.service.procurement.shipment.ShipmentDowntimeRiskEvent;

/**
 * FOR-11-04 task 5.2 (part 5): {@link ProcurementDeadlineSweepService#sweep()} against PostgreSQL: the
 * order-by and downtime candidates, the one-shot markers, idempotence on a second run, re-arming by a new
 * date, and the exclusions. Events are observed with {@code @RecordApplicationEvents}; the transaction is
 * rolled back, so no after-commit listener ever fires.
 *
 * <p>Repeatability: unique per-run data inside a rolled-back transaction.
 *
 * <p>Validates Requirements 14.2 - 14.5.
 */
@RecordApplicationEvents
class DeadlineSweepIntegrationTest extends ProcurementIntegrationTestBase {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    /** Works of the category start 2 days after the project start (half-day 3). */
    private static final LocalDate PROJECT_START = LocalDate.of(2031, 3, 3);
    private static final LocalDate WORKS_START = PROJECT_START.plusDays(1);

    @Autowired
    private ProcurementDeadlineSweepService sweep;
    @Autowired
    private ApplicationEvents events;

    private static LocalDate warsawToday() {
        return LocalDate.now(WARSAW);
    }

    private WorkCategoryEntity category(String key) {
        WorkCategoryEntity c = new WorkCategoryEntity();
        c.setCode("ds_" + key + "_" + unique());
        c.setOrderNo(1);
        c.setNameRU("Кат " + key);
        c.setNamePL("Kat " + key);
        c.setActive(true);
        em.persist(c);
        return c;
    }

    /** A project with a schedule whose single bar of {@code category} starts at {@link #WORKS_START}. */
    private ProjectEntity scheduledProject(WorkCategoryEntity category) {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        project.setStartDate(PROJECT_START);
        ProjectScheduleEntity schedule = new ProjectScheduleEntity();
        schedule.setProject(project);
        ProjectScheduleBarEntity bar = new ProjectScheduleBarEntity();
        bar.setSchedule(schedule);
        bar.setWorkCategory(category);
        bar.setStartHalfDays(3);
        bar.setDurationHalfDays(4);
        schedule.getBars().add(bar);
        em.persist(schedule);
        em.flush();
        return project;
    }

    private ShipmentEntity shipment(ProjectEntity project, PurchasePositionEntity position,
                                    ShipmentStatus status, LocalDate eta) {
        ShipmentEntity s = new ShipmentEntity();
        s.setProject(project);
        s.setDeliveryMethod(DeliveryMethod.PHONE_CONTACT);
        s.setDriverPhone("+48 600 100 200");
        s.setStatus(status);
        s.setEtaDate(eta);
        s.setShippedAt(OffsetDateTime.now(ZoneOffset.UTC));
        em.persist(s);
        ShipmentLineEntity line = new ShipmentLineEntity();
        line.setShipment(s);
        line.setPosition(position);
        line.setQuantity(new java.math.BigDecimal("2.000"));
        em.persist(line);
        em.flush();
        return s;
    }

    private PurchasePositionEntity withOrderBy(ProjectEntity project, PositionStatus status, LocalDate orderBy) {
        PurchasePositionEntity p = position(project, SourcingPath.AGREED, status);
        p.setOrderByDate(orderBy);
        em.flush();
        return p;
    }

    private List<Long> orderByEventsFor(Long... positionIds) {
        List<Long> wanted = List.of(positionIds);
        return events.stream(PositionOrderByMissedEvent.class)
                .map(PositionOrderByMissedEvent::positionId).filter(wanted::contains).toList();
    }

    private List<Long> downtimeEventsFor(Long... shipmentIds) {
        List<Long> wanted = List.of(shipmentIds);
        return events.stream(ShipmentDowntimeRiskEvent.class)
                .map(ShipmentDowntimeRiskEvent::shipmentId).filter(wanted::contains).toList();
    }

    private PurchasePositionEntity fresh(Long id) {
        em.flush();
        em.clear();
        return em.find(PurchasePositionEntity.class, id);
    }

    private ShipmentEntity freshShipment(Long id) {
        em.flush();
        em.clear();
        return em.find(ShipmentEntity.class, id);
    }

    @Test
    @DisplayName("order-by: fires once per distinct date, stamps the marker, ignores ORDERED / CLIENT_OWN / future / cancelled")
    void orderBy_oneShotAndRearmed() {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        LocalDate missed = warsawToday().minusDays(3);
        PurchasePositionEntity hit = withOrderBy(project, PositionStatus.PLANNED, missed);
        PurchasePositionEntity onToday = withOrderBy(project, PositionStatus.WITH_BUYER, warsawToday());
        PurchasePositionEntity future = withOrderBy(project, PositionStatus.PLANNED, warsawToday().plusDays(2));
        PurchasePositionEntity ordered = withOrderBy(project, PositionStatus.ORDERED, missed);
        PurchasePositionEntity cancelled = withOrderBy(project, PositionStatus.CANCELLED, missed);
        PurchasePositionEntity own = position(project, SourcingPath.CLIENT_OWN, PositionStatus.PLANNED);
        own.setOrderByDate(missed);
        em.flush();

        sweep.sweep();

        assertThat(orderByEventsFor(hit.getId(), onToday.getId(), future.getId(), ordered.getId(),
                cancelled.getId(), own.getId())).containsExactlyInAnyOrder(hit.getId(), onToday.getId());
        assertThat(fresh(hit.getId()).getOrderByNotifiedFor()).isEqualTo(missed);
        assertThat(fresh(onToday.getId()).getOrderByNotifiedFor()).isEqualTo(warsawToday());
        assertThat(fresh(future.getId()).getOrderByNotifiedFor()).isNull();
        assertThat(fresh(ordered.getId()).getOrderByNotifiedFor()).isNull();

        // idempotent: a second run emits nothing for the same dates
        events.clear();
        sweep.sweep();
        assertThat(orderByEventsFor(hit.getId(), onToday.getId())).isEmpty();

        // a new order-by date re-arms the marker without any reset code
        PurchasePositionEntity again = fresh(hit.getId());
        again.setOrderByDate(warsawToday().minusDays(1));
        em.flush();
        events.clear();
        sweep.sweep();
        assertThat(orderByEventsFor(hit.getId(), onToday.getId())).containsExactly(hit.getId());
        assertThat(fresh(hit.getId()).getOrderByNotifiedFor()).isEqualTo(warsawToday().minusDays(1));
    }

    @Test
    @DisplayName("downtime: ETA after the works start fires once per ETA value; a new ETA re-arms; earlier ETA does not fire")
    void downtime_oneShotAndRearmed() {
        WorkCategoryEntity category = category("A");
        ProjectEntity project = scheduledProject(category);
        PurchasePositionEntity late = position(project, SourcingPath.AGREED, PositionStatus.IN_DELIVERY);
        late.setWorkCategory(category);
        PurchasePositionEntity onTime = position(project, SourcingPath.AGREED, PositionStatus.IN_DELIVERY);
        onTime.setWorkCategory(category);
        PurchasePositionEntity noCategory = position(project, SourcingPath.AGREED, PositionStatus.IN_DELIVERY);
        em.flush();
        LocalDate lateEta = WORKS_START.plusDays(4);
        ShipmentEntity lateShipment = shipment(project, late, ShipmentStatus.IN_TRANSIT, lateEta);
        ShipmentEntity onTimeShipment = shipment(project, onTime, ShipmentStatus.IN_TRANSIT, WORKS_START);
        ShipmentEntity noCategoryShipment = shipment(project, noCategory, ShipmentStatus.IN_TRANSIT, lateEta);
        ShipmentEntity deliveredShipment = shipment(project, late, ShipmentStatus.DELIVERED, lateEta.plusDays(1));

        sweep.sweep();

        assertThat(downtimeEventsFor(lateShipment.getId(), onTimeShipment.getId(), noCategoryShipment.getId(),
                deliveredShipment.getId())).containsExactly(lateShipment.getId());
        assertThat(freshShipment(lateShipment.getId()).getDowntimeNotifiedFor()).isEqualTo(lateEta);
        assertThat(freshShipment(onTimeShipment.getId()).getDowntimeNotifiedFor()).isNull();
        assertThat(freshShipment(noCategoryShipment.getId()).getDowntimeNotifiedFor()).isNull();
        assertThat(freshShipment(deliveredShipment.getId()).getDowntimeNotifiedFor()).isNull();
        ShipmentDowntimeRiskEvent fired = events.stream(ShipmentDowntimeRiskEvent.class)
                .filter(e -> e.shipmentId().equals(lateShipment.getId())).findFirst().orElseThrow();
        assertThat(fired.etaDate()).isEqualTo(lateEta);
        assertThat(fired.projectId()).isEqualTo(project.getId());

        // idempotent
        events.clear();
        sweep.sweep();
        assertThat(downtimeEventsFor(lateShipment.getId())).isEmpty();

        // a different ETA that is still late re-arms the notification
        ShipmentEntity moved = freshShipment(lateShipment.getId());
        moved.setEtaDate(lateEta.plusDays(3));
        em.flush();
        events.clear();
        sweep.sweep();
        assertThat(downtimeEventsFor(lateShipment.getId())).containsExactly(lateShipment.getId());
        assertThat(freshShipment(lateShipment.getId()).getDowntimeNotifiedFor()).isEqualTo(lateEta.plusDays(3));
    }

    @Test
    @DisplayName("downtime: a project without a schedule and a received position are not evaluated")
    void downtime_noScheduleOrReceived() {
        WorkCategoryEntity category = category("B");
        ProjectEntity noSchedule = project(ProjectStatus.ACTIVE);
        noSchedule.setStartDate(PROJECT_START);
        PurchasePositionEntity p1 = position(noSchedule, SourcingPath.AGREED, PositionStatus.IN_DELIVERY);
        p1.setWorkCategory(category);
        ShipmentEntity s1 = shipment(noSchedule, p1, ShipmentStatus.IN_TRANSIT, WORKS_START.plusDays(5));

        ProjectEntity scheduled = scheduledProject(category);
        PurchasePositionEntity received = position(scheduled, SourcingPath.AGREED, PositionStatus.RECEIVED);
        received.setWorkCategory(category);
        ShipmentEntity s2 = shipment(scheduled, received, ShipmentStatus.IN_TRANSIT, WORKS_START.plusDays(5));

        sweep.sweep();

        assertThat(downtimeEventsFor(s1.getId(), s2.getId())).isEmpty();
        assertThat(freshShipment(s1.getId()).getDowntimeNotifiedFor()).isNull();
        assertThat(freshShipment(s2.getId()).getDowntimeNotifiedFor()).isNull();
    }
}
