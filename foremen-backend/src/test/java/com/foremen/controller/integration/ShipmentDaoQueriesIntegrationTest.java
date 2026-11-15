package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import com.foremen.dao.ShipmentDao;
import com.foremen.dao.ShipmentEventDao;
import com.foremen.dao.ShipmentLineDao;
import com.foremen.dao.model.DeliveryMethod;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectScheduleEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.ShipmentEntity;
import com.foremen.dao.model.ShipmentEventEntity;
import com.foremen.dao.model.ShipmentLineEntity;
import com.foremen.dao.model.ShipmentSource;
import com.foremen.dao.model.ShipmentStatus;
import com.foremen.dao.model.SourcingPath;

/**
 * FOR-11-04 task 3.1 - every query of {@link ShipmentDao}, {@link ShipmentLineDao} and {@link ShipmentEventDao}
 * executes against PostgreSQL with real data, and {@code PurchasePositionEntity.orderByNotifiedFor} round-trips.
 *
 * <p>Repeatability: unique per-run data inside a rolled-back transaction.
 *
 * <p>Validates Requirements 2.1, 2.2, 2.3, 4.4.
 */
class ShipmentDaoQueriesIntegrationTest extends ProcurementIntegrationTestBase {

    @Autowired
    private ShipmentDao shipmentDao;
    @Autowired
    private ShipmentLineDao lineDao;
    @Autowired
    private ShipmentEventDao eventDao;

    private ShipmentEntity shipment(ProjectEntity project, DeliveryMethod method, ShipmentStatus status,
                                    LocalDate eta, OffsetDateTime nextCheckAt) {
        ShipmentEntity s = new ShipmentEntity();
        s.setProject(project);
        s.setDeliveryMethod(method);
        s.setStatus(status);
        s.setEtaDate(eta);
        s.setNextCheckAt(nextCheckAt);
        s.setShippedAt(OffsetDateTime.now(ZoneOffset.UTC));
        shipmentDao.save(s);
        em.flush();
        return s;
    }

    private ShipmentLineEntity line(ShipmentEntity s, PurchasePositionEntity p, String qty) {
        ShipmentLineEntity l = new ShipmentLineEntity();
        l.setShipment(s);
        l.setPosition(p);
        l.setQuantity(new BigDecimal(qty));
        lineDao.save(l);
        em.flush();
        return l;
    }

    @Test
    @DisplayName("ShipmentDao: due-for-tracking, active-with-ETA per project, projects with a schedule")
    void shipmentQueries() {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        LocalDate eta = LocalDate.now().plusDays(3);

        ShipmentEntity neverChecked = shipment(project, DeliveryMethod.CARRIER, ShipmentStatus.CREATED, eta, null);
        ShipmentEntity due = shipment(project, DeliveryMethod.CARRIER, ShipmentStatus.IN_TRANSIT, eta, now.minusHours(1));
        shipment(project, DeliveryMethod.CARRIER, ShipmentStatus.IN_TRANSIT, eta, now.plusHours(1));
        shipment(project, DeliveryMethod.CARRIER, ShipmentStatus.DELIVERED, eta, now.minusHours(1));
        shipment(project, DeliveryMethod.SHOP_DELIVERY, ShipmentStatus.IN_TRANSIT, eta, now.minusHours(1));
        ShipmentEntity noEta = shipment(project, DeliveryMethod.PHONE_CONTACT, ShipmentStatus.CREATED, null, null);

        List<ShipmentEntity> dueList = shipmentDao.findDueForTracking(now, PageRequest.of(0, 100));
        List<Long> dueIds = dueList.stream().map(ShipmentEntity::getId).toList();
        assertThat(dueIds).contains(neverChecked.getId(), due.getId());
        assertThat(dueList).allMatch(s -> s.getDeliveryMethod() == DeliveryMethod.CARRIER
                && s.getStatus() != ShipmentStatus.DELIVERED && s.getStatus() != ShipmentStatus.CANCELLED);
        assertThat(dueIds.indexOf(neverChecked.getId())).isLessThan(dueIds.indexOf(due.getId()));

        List<ShipmentEntity> withEta = shipmentDao.findActiveWithEtaByProjectId(project.getId());
        assertThat(withEta).hasSize(4).doesNotContain(noEta);

        assertThat(shipmentDao.findActiveWithEtaForProjectsWithSchedule())
                .extracting(ShipmentEntity::getId).doesNotContain(neverChecked.getId());
        ProjectScheduleEntity schedule = new ProjectScheduleEntity();
        schedule.setProject(project);
        em.persist(schedule);
        em.flush();
        List<ShipmentEntity> swept = shipmentDao.findActiveWithEtaForProjectsWithSchedule();
        assertThat(swept).extracting(ShipmentEntity::getId).contains(neverChecked.getId(), due.getId())
                .doesNotContain(noEta.getId());
    }

    @Test
    @DisplayName("ShipmentLineDao: line lookup, active quantity sums, active lines, existsActiveWithEta, delete")
    void lineQueries() {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity p1 = position(project, SourcingPath.AGREED, PositionStatus.ORDERED);
        PurchasePositionEntity p2 = position(project, SourcingPath.AGREED, PositionStatus.ORDERED);
        LocalDate eta = LocalDate.now().plusDays(2);

        ShipmentEntity active = shipment(project, DeliveryMethod.CARRIER, ShipmentStatus.IN_TRANSIT, eta, null);
        ShipmentEntity delivered = shipment(project, DeliveryMethod.CARRIER, ShipmentStatus.DELIVERED, eta, null);
        ShipmentEntity cancelled = shipment(project, DeliveryMethod.CARRIER, ShipmentStatus.CANCELLED, eta, null);
        ShipmentEntity noEta = shipment(project, DeliveryMethod.PHONE_CONTACT, ShipmentStatus.CREATED, null, null);

        line(active, p1, "2.000");
        line(delivered, p1, "1.500");
        line(cancelled, p1, "9.000");
        line(noEta, p2, "4.000");
        em.clear();

        assertThat(lineDao.findByShipmentId(active.getId())).hasSize(1)
                .allMatch(l -> l.getPosition().getId().equals(p1.getId()));

        // cancelled is excluded, delivered counts
        assertThat(lineDao.sumActiveQuantityByPositionId(p1.getId())).isEqualByComparingTo("3.500");
        assertThat(lineDao.sumActiveQuantityByPositionId(-5L)).isEqualByComparingTo("0");

        List<Object[]> sums = lineDao.sumActiveQuantityByPositionExcluding(List.of(p1.getId(), p2.getId()), -1L);
        assertThat(sums).hasSize(2);
        Object[] p1Row = sums.stream().filter(r -> r[0].equals(p1.getId())).findFirst().orElseThrow();
        assertThat((BigDecimal) p1Row[1]).isEqualByComparingTo("3.500");
        List<Object[]> excluding = lineDao.sumActiveQuantityByPositionExcluding(List.of(p1.getId()), active.getId());
        assertThat(excluding).hasSize(1);
        assertThat((BigDecimal) excluding.get(0)[1]).isEqualByComparingTo("1.500");

        List<ShipmentLineEntity> activeLines = lineDao.findActiveByPositionIds(List.of(p1.getId(), p2.getId()));
        assertThat(activeLines).extracting(l -> l.getShipment().getId())
                .containsExactlyInAnyOrder(active.getId(), noEta.getId());

        assertThat(lineDao.existsActiveWithEta(p1.getId())).isTrue();
        assertThat(lineDao.existsActiveWithEta(p2.getId())).isFalse();

        assertThat(lineDao.deleteByShipmentId(active.getId())).isEqualTo(1);
        em.clear();
        assertThat(lineDao.existsActiveWithEta(p1.getId())).isFalse();
    }

    @Test
    @DisplayName("ShipmentEventDao: history is ordered by occurredAt")
    void eventQueries() {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        ShipmentEntity s = shipment(project, DeliveryMethod.CARRIER, ShipmentStatus.CREATED, null, null);
        OffsetDateTime t = OffsetDateTime.now(ZoneOffset.UTC);
        ShipmentEventEntity later = event(s, ShipmentStatus.IN_TRANSIT, t);
        ShipmentEventEntity earlier = event(s, ShipmentStatus.CREATED, t.minusHours(2));
        em.clear();

        List<ShipmentEventEntity> history = eventDao.findByShipmentId(s.getId());
        assertThat(history).extracting(ShipmentEventEntity::getId).containsExactly(earlier.getId(), later.getId());
        assertThat(history.get(0).getStatus()).isEqualTo(ShipmentStatus.CREATED);
        assertThat(history.get(0).getSource()).isEqualTo(ShipmentSource.MANUAL);
    }

    @Test
    @DisplayName("PurchasePositionEntity.orderByNotifiedFor round-trips")
    void orderByNotifiedFor() {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity p = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        LocalDate d = LocalDate.now().plusDays(5);
        p.setOrderByNotifiedFor(d);
        em.flush();
        em.clear();
        assertThat(em.find(PurchasePositionEntity.class, p.getId()).getOrderByNotifiedFor()).isEqualTo(d);
    }

    private ShipmentEventEntity event(ShipmentEntity s, ShipmentStatus status, OffsetDateTime at) {
        ShipmentEventEntity e = new ShipmentEventEntity();
        e.setShipment(s);
        e.setStatus(status);
        e.setOccurredAt(at);
        e.setSource(ShipmentSource.MANUAL);
        eventDao.save(e);
        em.flush();
        return e;
    }
}
