package com.foremen.service.procurement;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.ShipmentLineDao;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.dao.model.ShipmentLineEntity;
import com.foremen.service.procurement.shipment.ShipmentRules;

/**
 * FOR-11-00 (R11.11): the single writer of {@code PurchasePositionEntity.plannedDeliveryDate}. The value
 * mirrors the request's {@code expectedDeliveryDate}: set on attach, cleared on detach, re-applied to
 * every non-terminal position when the request date changes. Terminal positions ({@code RECEIVED},
 * {@code CANCELLED}) keep their last value on a date change.
 *
 * <p>FOR-11-04 (R11.1, R11.2): an Active_Shipment ETA takes precedence over the request date.
 */
@Component
public class PlannedDeliveryDateSynchronizer {

    private final PurchasePositionDao positionDao;
    private final ShipmentLineDao shipmentLineDao;

    public PlannedDeliveryDateSynchronizer(PurchasePositionDao positionDao, ShipmentLineDao shipmentLineDao) {
        this.positionDao = positionDao;
        this.shipmentLineDao = shipmentLineDao;
    }

    /** Position joins a request: take the request's expected delivery date. */
    @Transactional
    public void onAttach(PurchasePositionEntity position, PurchaseRequestEntity request) {
        position.setPlannedDeliveryDate(request.getExpectedDeliveryDate());
    }

    /** Position leaves its request: no planned date any more. */
    @Transactional
    public void onDetach(PurchasePositionEntity position) {
        position.setPlannedDeliveryDate(null);
    }

    /**
     * Request date created or changed: re-apply to the request's non-terminal positions, skipping those
     * with an Active_Shipment ETA (R11.2).
     */
    @Transactional
    public void onRequestDateChanged(PurchaseRequestEntity request) {
        if (request.getId() == null) {
            return;
        }
        LocalDate date = request.getExpectedDeliveryDate();
        List<PurchasePositionEntity> positions = positionDao.findByPurchaseRequestId(request.getId());
        for (PurchasePositionEntity position : positions) {
            if (isTerminal(position.getStatus())) {
                continue;
            }
            if (shipmentLineDao.existsActiveWithEta(position.getId())) {
                continue;
            }
            position.setPlannedDeliveryDate(date);
        }
    }

    /**
     * FOR-11-04 (R11.1): recompute the planned date after a shipment changed: the latest ETA of the
     * position's Active_Shipments; without one, kept when a DELIVERED shipment exists, else the request's
     * expected date (or {@code null} without a request). Terminal positions keep their value.
     */
    @Transactional
    public void onShipmentChanged(Long positionId) {
        if (positionId == null) {
            return;
        }
        positionDao.findById(positionId).ifPresent(position -> {
            if (isTerminal(position.getStatus())) {
                return;
            }
            List<ShipmentRules.EtaCandidate> candidates = shipmentLineDao
                    .findActiveByPositionIds(List.of(positionId)).stream()
                    .map(ShipmentLineEntity::getShipment)
                    .map(s -> new ShipmentRules.EtaCandidate(s.getEtaDate()))
                    .toList();
            boolean hasDelivered = candidates.stream().noneMatch(c -> c.etaDate() != null)
                    && shipmentLineDao.existsDeliveredByPositionId(positionId);
            LocalDate requestDate = position.getPurchaseRequest() == null
                    ? null : position.getPurchaseRequest().getExpectedDeliveryDate();
            position.setPlannedDeliveryDate(ShipmentRules.effectiveEta(
                    candidates, requestDate, hasDelivered, position.getPlannedDeliveryDate()));
        });
    }

    static boolean isTerminal(PositionStatus status) {
        return status == PositionStatus.RECEIVED || status == PositionStatus.CANCELLED;
    }
}
