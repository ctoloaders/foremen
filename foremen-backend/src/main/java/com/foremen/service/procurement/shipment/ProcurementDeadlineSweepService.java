package com.foremen.service.procurement.shipment;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.ShipmentDao;
import com.foremen.dao.ShipmentLineDao;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.ShipmentEntity;
import com.foremen.dao.model.ShipmentLineEntity;
import com.foremen.service.procurement.schedule.MaterialScheduleRiskService;
import com.foremen.service.procurement.schedule.MaterialScheduleRiskService.ProjectCategory;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-11-04 (R14, design section 10): daily sweep that fires the one-shot "order-by missed" and
 * "downtime risk" events.
 *
 * <ul>
 *   <li><b>Order-by:</b> {@link PurchasePositionDao#findOrderByMissedCandidates(LocalDate)}; each
 *       candidate is stamped {@code orderByNotifiedFor = orderByDate} and a
 *       {@link PositionOrderByMissedEvent} is collected.</li>
 *   <li><b>Downtime:</b> every active shipment (with ETA, project with a schedule); when any line
 *       position has a downtime risk and {@code downtimeNotifiedFor} differs from the ETA, the marker is
 *       stamped and a {@link ShipmentDowntimeRiskEvent} is collected.</li>
 * </ul>
 * Events are published after all markers are staged; a failure for one item is logged and skipped.
 * Driven by {@code foremen.procurement.deadline-sweep.cron} ({@code "-"} disables it) in Europe/Warsaw.
 */
@Slf4j
@Service
public class ProcurementDeadlineSweepService {

    static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");

    private final PurchasePositionDao positionDao;
    private final ShipmentDao shipmentDao;
    private final ShipmentLineDao lineDao;
    private final MaterialScheduleRiskService riskService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Autowired
    public ProcurementDeadlineSweepService(PurchasePositionDao positionDao, ShipmentDao shipmentDao,
                                           ShipmentLineDao lineDao, MaterialScheduleRiskService riskService,
                                           ApplicationEventPublisher eventPublisher, Clock clock) {
        this.positionDao = positionDao;
        this.shipmentDao = shipmentDao;
        this.lineDao = lineDao;
        this.riskService = riskService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Scheduled(cron = "${foremen.procurement.deadline-sweep.cron:-}", zone = "Europe/Warsaw")
    @Transactional
    public void sweep() {
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        List<Object> events = new ArrayList<>();
        sweepOrderBy(today, events);
        sweepDowntime(events);

        // Publish after all markers are staged; the AFTER_COMMIT emitter never rolls this back.
        events.forEach(eventPublisher::publishEvent);
        if (!events.isEmpty()) {
            log.info("Procurement deadline sweep fired {} event(s)", events.size());
        }
    }

    private void sweepOrderBy(LocalDate today, List<Object> events) {
        for (PurchasePositionEntity position : positionDao.findOrderByMissedCandidates(today)) {
            try {
                position.setOrderByNotifiedFor(position.getOrderByDate());
                positionDao.save(position);
                events.add(new PositionOrderByMissedEvent(position.getId(), position.getProject().getId()));
            } catch (RuntimeException e) {
                log.warn("Deadline sweep: order-by check failed for position {}", position.getId(), e);
            }
        }
    }

    private void sweepDowntime(List<Object> events) {
        for (ShipmentEntity shipment : shipmentDao.findActiveWithEtaForProjectsWithSchedule()) {
            try {
                LocalDate eta = shipment.getEtaDate();
                if (eta == null || eta.equals(shipment.getDowntimeNotifiedFor())
                        || !hasDowntimeRisk(shipment, eta)) {
                    continue;
                }
                shipment.setDowntimeNotifiedFor(eta);
                shipmentDao.save(shipment);
                events.add(new ShipmentDowntimeRiskEvent(
                        shipment.getId(), shipment.getProject().getId(), eta, null));
            } catch (RuntimeException e) {
                log.warn("Deadline sweep: downtime check failed for shipment {}", shipment.getId(), e);
            }
        }
    }

    private boolean hasDowntimeRisk(ShipmentEntity shipment, LocalDate eta) {
        Long projectId = shipment.getProject().getId();
        List<PurchasePositionEntity> positions = lineDao.findByShipmentId(shipment.getId()).stream()
                .map(ShipmentLineEntity::getPosition)
                .filter(p -> p.getWorkCategory() != null)
                .toList();
        Set<ProjectCategory> pairs = new HashSet<>();
        for (PurchasePositionEntity p : positions) {
            pairs.add(new ProjectCategory(projectId, p.getWorkCategory().getId()));
        }
        if (pairs.isEmpty()) {
            return false;
        }
        Map<ProjectCategory, LocalDate> starts = riskService.worksStarts(pairs);
        for (PurchasePositionEntity p : positions) {
            LocalDate start = starts.get(new ProjectCategory(projectId, p.getWorkCategory().getId()));
            if (MaterialScheduleRiskService.downtimeRisk(p.getStatus(), eta, start)) {
                return true;
            }
        }
        return false;
    }
}
