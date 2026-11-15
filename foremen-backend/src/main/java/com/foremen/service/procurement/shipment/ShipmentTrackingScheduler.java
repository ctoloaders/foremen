package com.foremen.service.procurement.shipment;

import com.foremen.dao.ShipmentDao;
import com.foremen.dao.model.ShipmentEntity;
import com.foremen.service.procurement.shipment.tracking.CarrierThrottle;
import com.foremen.service.procurement.shipment.tracking.TrackingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FOR-11-04 (R9): refreshes due carrier shipments on the {@code foremen.procurement.tracking.cron} slots
 * (default {@code -}, disabled; recommended {@code 0 0 8,11,14,17 * * MON-FRI}). A run is a no-op while the
 * master switch is off. The batch ({@code ShipmentDao.findDueForTracking}, ordered by {@code next_check_at})
 * is processed sequentially, grouped by carrier; a paused carrier's remaining shipments are skipped, and one
 * failing shipment never stops the batch. Not transactional: the tracking service opens short transactions.
 *
 * <p>There is no distributed lock (single node, as {@code ProcurementOcrSweepService}). For a multi-node
 * deployment add ShedLock's {@code @SchedulerLock} on {@link #run()}.
 */
@Component
@Slf4j
public class ShipmentTrackingScheduler {

    private final ShipmentDao shipmentDao;
    private final ShipmentTrackingService trackingService;
    private final CarrierThrottle throttle;
    private final TrackingProperties properties;
    private final Clock clock;

    @Autowired
    public ShipmentTrackingScheduler(ShipmentDao shipmentDao, ShipmentTrackingService trackingService,
                                     CarrierThrottle throttle, TrackingProperties properties, Clock clock) {
        this.shipmentDao = shipmentDao;
        this.trackingService = trackingService;
        this.throttle = throttle;
        this.properties = properties;
        this.clock = clock;
    }

    /** Counters of one run; {@code selected = refreshed + unchanged + failed + skipped}. */
    public record Summary(int selected, int refreshed, int unchanged, int failed, int skipped) {
        static final Summary EMPTY = new Summary(0, 0, 0, 0, 0);
    }

    @Scheduled(cron = "${foremen.procurement.tracking.cron:-}",
            zone = "${foremen.procurement.tracking.zone:Europe/Warsaw}")
    public Summary run() {
        if (!properties.enabled()) {
            return Summary.EMPTY;
        }
        List<ShipmentEntity> due;
        try {
            due = shipmentDao.findDueForTracking(OffsetDateTime.now(clock),
                    PageRequest.of(0, properties.batchSize()));
        } catch (RuntimeException e) {
            log.warn("Shipment tracking run skipped, selection failed: {}", e.getClass().getSimpleName());
            return Summary.EMPTY;
        }
        Map<String, List<Long>> byCarrier = new LinkedHashMap<>();
        for (ShipmentEntity s : due) {
            byCarrier.computeIfAbsent(String.valueOf(s.getCarrierCode()), k -> new ArrayList<>()).add(s.getId());
        }
        int refreshed = 0;
        int unchanged = 0;
        int failed = 0;
        int skipped = 0;
        for (Map.Entry<String, List<Long>> group : byCarrier.entrySet()) {
            for (Long id : group.getValue()) {
                if (throttle.isPaused(group.getKey())) {
                    skipped++;
                    continue;
                }
                RefreshOutcome outcome;
                try {
                    outcome = trackingService.refresh(id, false);
                } catch (RuntimeException e) {
                    outcome = RefreshOutcome.of(RefreshOutcome.Result.FAILED, "OTHER");
                }
                switch (outcome.result()) {
                    case UPDATED -> refreshed++;
                    case UNCHANGED -> unchanged++;
                    case FAILED -> failed++;
                    default -> skipped++;
                }
            }
        }
        Summary summary = new Summary(due.size(), refreshed, unchanged, failed, skipped);
        log.info("Shipment tracking run: selected={}, refreshed={}, unchanged={}, failed={}, skipped={}",
                summary.selected(), refreshed, unchanged, failed, skipped);
        return summary;
    }
}
