package com.foremen.service.procurement.proforma;

import java.util.Collection;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.model.PurchasePositionEntity;

/**
 * FOR-11-06 (R10.6): clears {@code client_unpaid} of buyer-added positions when the proforma that bills
 * them is paid. Called by {@link ProformaReleaseService} inside the payment transaction, before
 * {@link ProformaPaidEvent} is published, so every {@code Delivery_Complete} read sees the cleared flag.
 */
@Component
public class ClientUnpaidClearer {

    private final PurchasePositionDao positionDao;

    public ClientUnpaidClearer(PurchasePositionDao positionDao) {
        this.positionDao = positionDao;
    }

    /** Idempotent: positions that are already unflagged are left alone. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void clear(Collection<Long> positionIds) {
        if (positionIds == null || positionIds.isEmpty()) {
            return;
        }
        for (PurchasePositionEntity p : positionDao.findAllByIdIn(positionIds)) {
            if (p.isClientUnpaid()) {
                p.setClientUnpaid(false);
                positionDao.save(p);
            }
        }
    }
}
