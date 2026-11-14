package com.foremen.service.procurement;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchaseRequestEntity;

/**
 * FOR-11-00 (R11.11): the single writer of {@code PurchasePositionEntity.plannedDeliveryDate}. The value
 * mirrors the request's {@code expectedDeliveryDate}: set on attach, cleared on detach, re-applied to
 * every non-terminal position when the request date changes. Terminal positions ({@code RECEIVED},
 * {@code CANCELLED}) keep their last value on a date change.
 */
@Component
public class PlannedDeliveryDateSynchronizer {

    private final PurchasePositionDao positionDao;

    public PlannedDeliveryDateSynchronizer(PurchasePositionDao positionDao) {
        this.positionDao = positionDao;
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

    /** Request date created or changed: re-apply to the request's non-terminal positions. */
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
            position.setPlannedDeliveryDate(date);
        }
    }

    static boolean isTerminal(PositionStatus status) {
        return status == PositionStatus.RECEIVED || status == PositionStatus.CANCELLED;
    }
}
