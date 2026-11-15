package com.foremen.service.procurement.shipment;

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
import com.foremen.service.procurement.shipment.tracking.CarrierTrackingAdapter;
import com.foremen.service.procurement.shipment.tracking.CarrierTrackingRegistry;
import com.foremen.service.procurement.shipment.tracking.TrackingFailure;
import com.foremen.service.procurement.shipment.tracking.TrackingProperties;
import com.foremen.service.procurement.shipment.tracking.TrackingResult;
import com.foremen.service.procurement.shipment.tracking.TrackingUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * FOR-11-04 (R7.5 to R7.9, R10): the carrier refresh flow.
 *
 * <p>{@link #refresh} runs in three steps: a short read transaction (guards: terminal / method / adapter,
 * manual cooldown, minimum shipment interval, carrier pause), the carrier call <b>outside</b> any
 * transaction (a slow carrier never holds a connection), then a short write transaction that applies the
 * result or records the failure. It never throws. Both transactions use {@code REQUIRES_NEW}, so the flow
 * also works from an after-commit listener.
 *
 * <p>Log lines carry the shipment id, the carrier code and the failure class only: never the tracking
 * number, owner data or a response body (R7.9).
 */
@Service
@Slf4j
public class ShipmentTrackingService {

    /** A number younger than this that the carrier does not know is "not yet registered" (R7.7). */
    static final Duration NOT_FOUND_GRACE = Duration.ofHours(24);

    private final ShipmentDao shipmentDao;
    private final ShipmentEventDao eventDao;
    private final ShipmentService shipmentService;
    private final CarrierTrackingRegistry registry;
    private final CarrierThrottle throttle;
    private final TrackingProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Autowired
    public ShipmentTrackingService(ShipmentDao shipmentDao, ShipmentEventDao eventDao,
                                   ShipmentService shipmentService, CarrierTrackingRegistry registry,
                                   CarrierThrottle throttle, TrackingProperties properties,
                                   PlatformTransactionManager txManager, Clock clock) {
        this.shipmentDao = shipmentDao;
        this.eventDao = eventDao;
        this.shipmentService = shipmentService;
        this.registry = registry;
        this.throttle = throttle;
        this.properties = properties;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** First refresh of a new, re-pointed or ETA-reverted carrier shipment (R4.8); failures are swallowed. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRefreshRequested(ShipmentRefreshRequestedEvent event) {
        try {
            refresh(event.shipmentId(), false);
        } catch (RuntimeException e) {
            log.warn("First tracking refresh of shipment {} failed: {}", event.shipmentId(), e.getClass().getSimpleName());
        }
    }

    /** Snapshot taken in the read step; carries only what the carrier call needs. */
    private record Target(String carrierCode, String number, CarrierTrackingAdapter adapter) {
    }

    /**
     * Refreshes one shipment from its carrier.
     *
     * @param manual {@code true} for a user refresh: bypasses the minimum shipment interval and enforces the
     *               per-shipment cooldown (R10.2, R10.3)
     */
    public RefreshOutcome refresh(Long shipmentId, boolean manual) {
        try {
            return doRefresh(shipmentId, manual);
        } catch (RuntimeException e) {
            log.warn("Tracking refresh of shipment {} failed unexpectedly: {}", shipmentId, e.getClass().getSimpleName());
            return RefreshOutcome.of(Result.FAILED, TrackingFailure.OTHER.name());
        }
    }

    private RefreshOutcome doRefresh(Long shipmentId, boolean manual) {
        RefreshOutcome[] rejection = new RefreshOutcome[1];
        Target target = tx.execute(status -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            ShipmentEntity s = shipmentDao.findById(shipmentId).orElse(null);
            Optional<CarrierTrackingAdapter> adapter = s == null || s.getStatus().isTerminal()
                    || s.getDeliveryMethod() != DeliveryMethod.CARRIER || s.getCarrierCode() == null
                    || s.getTrackingNumber() == null
                    ? Optional.empty() : registry.find(s.getCarrierCode());
            if (adapter.isEmpty()) {
                rejection[0] = unavailable(s, manual, now);
                return null;
            }
            if (manual && s.getLastCheckedAt() != null) {
                OffsetDateTime allowed = s.getLastCheckedAt().plus(properties.refreshCooldown());
                if (now.isBefore(allowed)) {
                    long millis = Duration.between(now, allowed).toMillis();
                    long wait = Math.max(1, (millis + 999) / 1000);
                    rejection[0] = new RefreshOutcome(Result.REJECTED, RefreshOutcome.REASON_TOO_SOON, wait);
                    return null;
                }
            }
            if (!manual && s.getNextCheckAt() != null && s.getNextCheckAt().isAfter(now)) {
                rejection[0] = RefreshOutcome.of(Result.SKIPPED, RefreshOutcome.REASON_NOT_DUE);
                return null;
            }
            if (throttle.isPaused(s.getCarrierCode())) {
                rejection[0] = RefreshOutcome.of(manual ? Result.REJECTED : Result.SKIPPED,
                        RefreshOutcome.REASON_CARRIER_PAUSED);
                return null;
            }
            return new Target(s.getCarrierCode(), s.getTrackingNumber(), adapter.get());
        });
        if (target == null) {
            return rejection[0] != null ? rejection[0]
                    : RefreshOutcome.of(Result.SKIPPED, RefreshOutcome.REASON_NOT_AVAILABLE);
        }

        // R7.6 / R10.3: per-adapter call spacing; wait one spacing interval at most, then give up.
        if (!throttle.tryAcquire(target.carrierCode())) {
            pause(properties.minCallSpacing());
            if (!throttle.tryAcquire(target.carrierCode())) {
                return RefreshOutcome.of(manual ? Result.REJECTED : Result.SKIPPED, RefreshOutcome.REASON_SPACING);
            }
        }

        TrackingResult result = null;
        TrackingFailure failure = null;
        try {
            result = target.adapter().fetch(target.number());
        } catch (TrackingUnavailableException e) {
            failure = e.getFailure();
        } catch (RuntimeException e) {
            failure = TrackingFailure.OTHER;
        }
        if (result == null && failure == null) {
            failure = TrackingFailure.PARSE_ERROR;
        }

        TrackingResult fetched = result;
        TrackingFailure failed = failure;
        RefreshOutcome outcome = tx.execute(status -> {
            ShipmentEntity s = shipmentDao.findById(shipmentId).orElse(null);
            if (s == null || s.getStatus().isTerminal()) {
                return RefreshOutcome.of(Result.SKIPPED, RefreshOutcome.REASON_NOT_AVAILABLE);
            }
            if (failed != null) {
                recordFailure(s, failed);
                return RefreshOutcome.of(Result.FAILED, failed.name());
            }
            return RefreshOutcome.of(apply(s, fetched) ? Result.UPDATED : Result.UNCHANGED);
        });
        if (failed != null) {
            log.warn("Tracking refresh of shipment {} ({}) failed: {}", shipmentId, target.carrierCode(), failed);
        }
        return outcome != null ? outcome : RefreshOutcome.of(Result.FAILED, TrackingFailure.OTHER.name());
    }

    /**
     * R7.8: applies a carrier result to {@code shipment} inside the caller's transaction: status per
     * {@link ShipmentRules#mayOverwriteStatus} (a {@code TRACKING} event only when it changed), ETA only when
     * {@code eta_source = TRACKING}, {@code last_event_at}, {@code last_checked_at}, failures reset,
     * {@code next_check_at = now + minShipmentInterval} (R9.3). Returns {@code true} when status or ETA
     * changed; then {@link ShipmentService#onStatusOrEtaChanged} recomputes the position dates and publishes
     * the delay / downtime events.
     */
    public boolean apply(ShipmentEntity shipment, TrackingResult result) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean statusChanged = false;
        if (result.status() != shipment.getStatus()
                && ShipmentRules.mayOverwriteStatus(shipment.getStatus(), shipment.getStatusSource(), result.status())) {
            shipment.setStatus(result.status());
            shipment.setStatusSource(ShipmentSource.TRACKING);
            statusChanged = true;
            ShipmentEventEntity event = new ShipmentEventEntity();
            event.setShipment(shipment);
            event.setStatus(result.status());
            event.setSource(ShipmentSource.TRACKING);
            event.setOccurredAt(result.lastEventAt() != null
                    ? result.lastEventAt().atOffset(now.getOffset()) : now);
            event.setNote(result.eventText());
            eventDao.save(event);
            if (result.status() == ShipmentStatus.DELIVERED) {
                shipment.setDeliveredAt(now);
            }
        }
        boolean etaChanged = false;
        if (shipment.getEtaSource() == EtaSource.TRACKING && result.etaDate() != null
                && !result.etaDate().equals(shipment.getEtaDate())) {
            shipment.setEtaDate(result.etaDate());
            etaChanged = true;
        }
        if (result.lastEventAt() != null) {
            shipment.setLastEventAt(result.lastEventAt().atOffset(now.getOffset()));
        }
        shipment.setLastCheckedAt(now);
        shipment.setConsecutiveFailures(0);
        shipment.setNextCheckAt(shipment.getStatus().isTerminal() ? null
                : now.plus(properties.minShipmentInterval()));
        shipmentDao.save(shipment);
        if (statusChanged || etaChanged) {
            shipmentService.onStatusOrEtaChanged(shipment, null);
            return true;
        }
        return false;
    }

    /** R7.6, R7.7: back-off, young {@code NOT_FOUND} grace, carrier pause on rate limit / block. */
    private void recordFailure(ShipmentEntity s, TrackingFailure failure) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean notYetRegistered = failure == TrackingFailure.NOT_FOUND && s.getShippedAt() != null
                && now.isBefore(s.getShippedAt().plus(NOT_FOUND_GRACE));
        s.setLastCheckedAt(now);
        if (notYetRegistered) {
            s.setNextCheckAt(now.plus(properties.minShipmentInterval()));
        } else {
            int failures = s.getConsecutiveFailures() + 1;
            s.setConsecutiveFailures(failures);
            s.setNextCheckAt(TrackingBackoff.nextCheck(now.toInstant(), failures,
                    properties.minShipmentInterval(), properties.backoffCap()).atOffset(now.getOffset()));
        }
        if (failure == TrackingFailure.RATE_LIMITED || failure == TrackingFailure.BLOCKED) {
            throttle.pause(s.getCarrierCode());
        }
        shipmentDao.save(s);
    }

    /**
     * No enabled adapter, terminal, or not a carrier shipment. A scheduler run pushes an open carrier
     * shipment out by the minimum interval so a disabled carrier does not fill every batch.
     */
    private RefreshOutcome unavailable(ShipmentEntity s, boolean manual, OffsetDateTime now) {
        if (!manual && s != null && !s.getStatus().isTerminal() && s.getDeliveryMethod() == DeliveryMethod.CARRIER) {
            s.setNextCheckAt(now.plus(properties.minShipmentInterval()));
            shipmentDao.save(s);
        }
        return RefreshOutcome.of(manual ? Result.REJECTED : Result.SKIPPED, RefreshOutcome.REASON_NOT_AVAILABLE);
    }

    /** Waits for the call-spacing interval (test seam). */
    void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
