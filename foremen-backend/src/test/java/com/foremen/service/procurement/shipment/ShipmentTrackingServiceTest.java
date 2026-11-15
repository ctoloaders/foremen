package com.foremen.service.procurement.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.foremen.dao.ShipmentDao;
import com.foremen.dao.ShipmentEventDao;
import com.foremen.dao.model.DeliveryMethod;
import com.foremen.dao.model.EtaSource;
import com.foremen.dao.model.ShipmentEntity;
import com.foremen.dao.model.ShipmentEventEntity;
import com.foremen.dao.model.ShipmentSource;
import com.foremen.dao.model.ShipmentStatus;
import com.foremen.service.procurement.shipment.RefreshOutcome.Result;
import com.foremen.service.procurement.shipment.tracking.CarrierThrottle;
import com.foremen.service.procurement.shipment.tracking.CarrierTrackingRegistry;
import com.foremen.service.procurement.shipment.tracking.StubTrackingAdapter;
import com.foremen.service.procurement.shipment.tracking.TrackingProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * FOR-11-04 (R7.5 to R7.9, R9, R10): refresh flow and scheduler driven by the real {@code STUB} adapter,
 * registry and throttle on a controllable clock; persistence is stubbed (no network, no database).
 */
class ShipmentTrackingServiceTest {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-03-10T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private MutableClock clock;
    private ShipmentDao shipmentDao;
    private ShipmentEventDao eventDao;
    private ShipmentService shipmentService;
    private TrackingProperties properties;
    private CarrierThrottle throttle;
    private ShipmentTrackingService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        shipmentDao = mock(ShipmentDao.class);
        eventDao = mock(ShipmentEventDao.class);
        shipmentService = mock(ShipmentService.class);
        properties = new TrackingProperties(true, null, null, 0, Duration.ofMinutes(30), Duration.ofSeconds(2),
                Duration.ofSeconds(60), Duration.ofHours(1), Duration.ofHours(24), null, null, 0, null,
                new TrackingProperties.Stub(true));
        CarrierTrackingRegistry registry = new CarrierTrackingRegistry(
                List.of(new StubTrackingAdapter(properties, clock)), properties);
        throttle = new CarrierThrottle(properties, clock);
        service = new ShipmentTrackingService(shipmentDao, eventDao, shipmentService, registry, throttle,
                properties, mock(PlatformTransactionManager.class), clock) {
            @Override
            void pause(Duration duration) {
                clock.advance(duration);
            }
        };
    }

    private ShipmentEntity shipment(long id, String number) {
        ShipmentEntity s = new ShipmentEntity();
        s.setId(id);
        s.setDeliveryMethod(DeliveryMethod.CARRIER);
        s.setCarrierCode("STUB");
        s.setTrackingNumber(number);
        s.setShippedAt(OffsetDateTime.now(clock).minusDays(3));
        when(shipmentDao.findById(id)).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    void successAppliesStatusEtaEventAndPropagates() {
        ShipmentEntity s = shipment(7L, "STUBTRANSIT1");

        RefreshOutcome outcome = service.refresh(7L, false);

        assertThat(outcome.result()).isEqualTo(Result.UPDATED);
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(s.getStatusSource()).isEqualTo(ShipmentSource.TRACKING);
        assertThat(s.getEtaDate()).isEqualTo(LocalDate.of(2026, 3, 12));
        assertThat(s.getConsecutiveFailures()).isZero();
        assertThat(s.getLastCheckedAt().toInstant()).isEqualTo(clock.now);
        assertThat(s.getNextCheckAt().toInstant()).isEqualTo(clock.now.plus(Duration.ofMinutes(30)));
        ArgumentCaptor<ShipmentEventEntity> event = ArgumentCaptor.forClass(ShipmentEventEntity.class);
        verify(eventDao).save(event.capture());
        assertThat(event.getValue().getSource()).isEqualTo(ShipmentSource.TRACKING);
        assertThat(event.getValue().getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        verify(shipmentService).onStatusOrEtaChanged(s, null);
    }

    @Test
    void unchangedResultAppendsNoEventAndDoesNotPropagate() {
        ShipmentEntity s = shipment(7L, "STUBTRANSIT1");
        service.refresh(7L, false);
        clock.advance(Duration.ofHours(1));

        RefreshOutcome second = service.refresh(7L, false);

        assertThat(second.result()).isEqualTo(Result.UNCHANGED);
        verify(eventDao, times(1)).save(any(ShipmentEventEntity.class));
        verify(shipmentService, times(1)).onStatusOrEtaChanged(eq(s), any());
    }

    @Test
    void notDueShipmentIsSkippedButManualRefreshBypassesTheInterval() {
        shipment(7L, "STUBTRANSIT1");
        service.refresh(7L, false);
        clock.advance(Duration.ofMinutes(5));

        assertThat(service.refresh(7L, false).reason()).isEqualTo(RefreshOutcome.REASON_NOT_DUE);
        assertThat(service.refresh(7L, true).result()).isEqualTo(Result.UNCHANGED);
    }

    @Test
    void manualRefreshWithinCooldownIsRejectedWithRetryAfter() {
        shipment(7L, "STUBTRANSIT1");
        service.refresh(7L, false);
        clock.advance(Duration.ofSeconds(20));

        RefreshOutcome outcome = service.refresh(7L, true);

        assertThat(outcome.result()).isEqualTo(Result.REJECTED);
        assertThat(outcome.reason()).isEqualTo(RefreshOutcome.REASON_TOO_SOON);
        assertThat(outcome.retryAfterSeconds()).isEqualTo(40L);
    }

    @Test
    void failureBacksOffExponentiallyAndCountsFailures() {
        ShipmentEntity s = shipment(7L, "STUBFAIL1");

        RefreshOutcome first = service.refresh(7L, false);
        assertThat(first.result()).isEqualTo(Result.FAILED);
        assertThat(first.reason()).isEqualTo("TIMEOUT");
        assertThat(s.getConsecutiveFailures()).isEqualTo(1);
        assertThat(s.getNextCheckAt().toInstant()).isEqualTo(clock.now.plus(Duration.ofHours(1)));

        clock.advance(Duration.ofHours(1));
        service.refresh(7L, false);
        assertThat(s.getConsecutiveFailures()).isEqualTo(2);
        assertThat(s.getNextCheckAt().toInstant()).isEqualTo(clock.now.plus(Duration.ofHours(2)));
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.CREATED);
        verify(shipmentService, never()).onStatusOrEtaChanged(any(), any());
    }

    @Test
    void blockedFailurePausesTheCarrierForTheCooldown() {
        shipment(7L, "STUBBLOCK1");
        assertThat(service.refresh(7L, false).reason()).isEqualTo("BLOCKED");
        assertThat(throttle.isPaused("STUB")).isTrue();
        clock.advance(Duration.ofSeconds(61)); // past the manual cooldown, still inside the 1 h pause

        RefreshOutcome next = service.refresh(7L, true);
        assertThat(next.result()).isEqualTo(Result.REJECTED);
        assertThat(next.reason()).isEqualTo(RefreshOutcome.REASON_CARRIER_PAUSED);

        clock.advance(Duration.ofHours(1).minusSeconds(61));
        assertThat(service.refresh(7L, true).reason()).isEqualTo("BLOCKED");
    }

    @Test
    void callSpacingIsHonouredBetweenTwoShipmentsOfOneCarrier() {
        shipment(7L, "STUBTRANSIT1");
        shipment(8L, "STUBTRANSIT2");
        Instant start = clock.now;

        service.refresh(7L, false);
        service.refresh(8L, false);

        assertThat(clock.now).isEqualTo(start.plus(Duration.ofSeconds(2)));
    }

    @Test
    void manualStatusAndEtaAreNotOverwritten() {
        ShipmentEntity s = shipment(7L, "STUBTRANSIT1");
        s.setStatus(ShipmentStatus.OUT_FOR_DELIVERY);
        s.setStatusSource(ShipmentSource.MANUAL);
        s.setEtaSource(EtaSource.MANUAL);
        s.setEtaDate(LocalDate.of(2026, 3, 11));

        RefreshOutcome outcome = service.refresh(7L, false);

        assertThat(outcome.result()).isEqualTo(Result.UNCHANGED);
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(s.getEtaDate()).isEqualTo(LocalDate.of(2026, 3, 11));
        verify(shipmentService, never()).onStatusOrEtaChanged(any(), any());
    }

    @Test
    void deliveredStampsDateAndStopsChecking() {
        ShipmentEntity s = shipment(7L, "STUBDELIV1");

        service.refresh(7L, false);

        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(s.getDeliveredAt()).isNotNull();
        assertThat(s.getNextCheckAt()).isNull();
        clock.advance(Duration.ofMinutes(2));
        assertThat(service.refresh(7L, true).reason()).isEqualTo(RefreshOutcome.REASON_NOT_AVAILABLE);
    }

    @Test
    void missingAdapterIsRejectedWhenManualAndPushedOutWhenScheduled() {
        ShipmentEntity s = shipment(7L, "X1234");
        s.setCarrierCode("DPD");

        RefreshOutcome manual = service.refresh(7L, true);
        assertThat(manual.result()).isEqualTo(Result.REJECTED);
        assertThat(manual.reason()).isEqualTo(RefreshOutcome.REASON_NOT_AVAILABLE);
        assertThat(s.getNextCheckAt()).isNull();

        assertThat(service.refresh(7L, false).result()).isEqualTo(Result.SKIPPED);
        assertThat(s.getNextCheckAt()).isNotNull();
    }

    @Test
    void refreshRequestedListenerSwallowsFailures() {
        when(shipmentDao.findById(9L)).thenThrow(new IllegalStateException("db down"));

        service.onRefreshRequested(new ShipmentRefreshRequestedEvent(9L));

        assertThat(service.refresh(9L, false).result()).isEqualTo(Result.FAILED);
    }

    @Test
    void schedulerIsNoOpWhenMasterSwitchIsOff() {
        TrackingProperties off = new TrackingProperties(false, null, null, 0, null, null, null, null, null,
                null, null, 0, null, null);
        ShipmentTrackingScheduler scheduler = new ShipmentTrackingScheduler(shipmentDao, service,
                new CarrierThrottle(off, clock), off, clock);

        assertThat(scheduler.run().selected()).isZero();
        verify(shipmentDao, never()).findDueForTracking(any(), any());
    }

    @Test
    void schedulerProcessesTheBatchAndOneFailureDoesNotStopIt() {
        ShipmentEntity bad = shipment(8L, "STUBFAIL2");
        ShipmentEntity ok = shipment(7L, "STUBTRANSIT1");
        when(shipmentDao.findDueForTracking(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(bad, ok));
        ShipmentTrackingScheduler scheduler = new ShipmentTrackingScheduler(shipmentDao, service, throttle,
                properties, clock);

        ShipmentTrackingScheduler.Summary summary = scheduler.run();

        assertThat(summary).isEqualTo(new ShipmentTrackingScheduler.Summary(2, 1, 0, 1, 0));
        assertThat(ok.getStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(bad.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void schedulerSkipsTheRestOfAPausedCarrier() {
        ShipmentEntity blocked = shipment(8L, "STUBBLOCK2");
        ShipmentEntity next = shipment(7L, "STUBTRANSIT1");
        when(shipmentDao.findDueForTracking(any(OffsetDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(blocked, next));
        ShipmentTrackingScheduler scheduler = new ShipmentTrackingScheduler(shipmentDao, service, throttle,
                properties, clock);

        ShipmentTrackingScheduler.Summary summary = scheduler.run();

        assertThat(summary).isEqualTo(new ShipmentTrackingScheduler.Summary(2, 0, 0, 1, 1));
        assertThat(next.getStatus()).isEqualTo(ShipmentStatus.CREATED);
    }
}
