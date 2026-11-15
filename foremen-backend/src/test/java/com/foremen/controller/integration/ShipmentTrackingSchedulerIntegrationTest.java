package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.foremen.dao.model.DeliveryMethod;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ShipmentEntity;
import com.foremen.dao.model.ShipmentStatus;
import com.foremen.service.procurement.shipment.ShipmentTrackingScheduler;
import com.foremen.service.procurement.shipment.ShipmentTrackingScheduler.Summary;

/**
 * FOR-11-04 task 5.2 (part 2): {@link ShipmentTrackingScheduler#run()} and its selection query against
 * PostgreSQL with the {@code STUB} adapter. The cron itself is off; the run is called directly.
 *
 * <p>Repeatability: committed data, wiped per test ({@link ShipmentTrackingCommittedIntegrationBase}).
 *
 * <p>Validates Requirements 7.6, 7.7, 9.2 - 9.4, 9.6.
 */
class ShipmentTrackingSchedulerIntegrationTest extends ShipmentTrackingCommittedIntegrationBase {

    @Autowired
    private ShipmentTrackingScheduler scheduler;

    private static String number(String prefix) {
        return prefix + System.nanoTime();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("selects only due, open CARRIER shipments; refreshes, backs off, skips and is idempotent")
    void run_selectionAndSecondRunIdempotent() {
        Long project = newProject();
        Long transit = newStubShipment(project, newPosition(project, PositionStatus.IN_DELIVERY),
                number("STUBTRANSIT"), ShipmentStatus.CREATED, null);
        Long failing = newStubShipment(project, newPosition(project, PositionStatus.IN_DELIVERY),
                number("STUBFAIL"), ShipmentStatus.IN_TRANSIT, now().minusHours(1));
        Long noAdapter = newStubShipment(project, null, number("ZZ"), ShipmentStatus.CREATED, now().minusHours(2));
        Long future = newStubShipment(project, null, number("STUBTRANSIT"), ShipmentStatus.CREATED,
                now().plusHours(3));
        Long delivered = newStubShipment(project, null, number("STUBTRANSIT"), ShipmentStatus.DELIVERED,
                now().minusHours(1));
        Long cancelled = newStubShipment(project, null, number("STUBTRANSIT"), ShipmentStatus.CANCELLED,
                now().minusHours(1));
        Long shop = newStubShipment(project, null, null, ShipmentStatus.CREATED, now().minusHours(1));
        tx().executeWithoutResult(t -> {
            ShipmentEntity u = shipmentDao.findById(noAdapter).orElseThrow();
            u.setCarrierCode("NOPE");
            shipmentDao.save(u);
            ShipmentEntity s = shipmentDao.findById(shop).orElseThrow();
            s.setDeliveryMethod(DeliveryMethod.SHOP_DELIVERY);
            s.setCarrierCode(null);
            shipmentDao.save(s);
        });
        OffsetDateTime futureBefore = reload(future).getNextCheckAt();

        Summary first = scheduler.run();

        assertThat(first.selected()).isEqualTo(3);
        assertThat(first.refreshed()).isEqualTo(1);
        assertThat(first.failed()).isEqualTo(1);
        assertThat(first.skipped()).isEqualTo(1);
        assertThat(first.unchanged()).isZero();

        ShipmentEntity t = reload(transit);
        assertThat(t.getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(Duration.between(t.getLastCheckedAt(), t.getNextCheckAt()))
                .isBetween(Duration.ofMinutes(29), Duration.ofMinutes(31));
        ShipmentEntity f = reload(failing);
        assertThat(f.getConsecutiveFailures()).isEqualTo(1);
        assertThat(Duration.between(f.getLastCheckedAt(), f.getNextCheckAt()))
                .isBetween(Duration.ofMinutes(59), Duration.ofMinutes(61));
        // a carrier without an enabled adapter is pushed out so it does not fill every batch
        assertThat(reload(noAdapter).getNextCheckAt()).isAfter(now().plusMinutes(25));
        assertThat(reload(noAdapter).getLastCheckedAt()).isNull();
        // everything else is untouched
        assertThat(reload(future).getNextCheckAt()).isEqualTo(futureBefore);
        assertThat(reload(future).getLastCheckedAt()).isNull();
        assertThat(reload(delivered).getLastCheckedAt()).isNull();
        assertThat(reload(cancelled).getLastCheckedAt()).isNull();
        assertThat(reload(shop).getLastCheckedAt()).isNull();

        Summary second = scheduler.run();
        assertThat(second.selected()).isZero();
        assertThat(reload(transit).getLastCheckedAt()).isEqualTo(t.getLastCheckedAt());
        assertThat(reload(failing).getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("a blocked carrier is paused for the rest of the batch: later shipments are skipped, not called")
    void run_blockedCarrierSkipsRemainder() {
        Long project = newProject();
        Long blocker = newStubShipment(project, newPosition(project, PositionStatus.IN_DELIVERY),
                number("STUBBLOCK"), ShipmentStatus.CREATED, now().minusHours(2));
        Long next = newStubShipment(project, newPosition(project, PositionStatus.IN_DELIVERY),
                number("STUBTRANSIT"), ShipmentStatus.CREATED, now().minusHours(1));

        Summary summary = scheduler.run();

        assertThat(summary.selected()).isEqualTo(2);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.refreshed()).isZero();
        assertThat(reload(blocker).getConsecutiveFailures()).isEqualTo(1);
        assertThat(reload(next).getLastCheckedAt()).isNull();
        assertThat(reload(next).getStatus()).isEqualTo(ShipmentStatus.CREATED);
    }

    @Test
    @DisplayName("a failing shipment does not stop the batch")
    void run_failureDoesNotStopBatch() {
        Long project = newProject();
        Long failing = newStubShipment(project, newPosition(project, PositionStatus.IN_DELIVERY),
                number("STUBFAIL"), ShipmentStatus.CREATED, now().minusHours(2));
        Long ok = newStubShipment(project, newPosition(project, PositionStatus.IN_DELIVERY),
                number("STUBDELIV"), ShipmentStatus.CREATED, now().minusHours(1));

        Summary summary = scheduler.run();

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.refreshed()).isEqualTo(1);
        assertThat(reload(failing).getConsecutiveFailures()).isEqualTo(1);
        ShipmentEntity delivered = reload(ok);
        assertThat(delivered.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(delivered.getNextCheckAt()).isNull();
    }
}
