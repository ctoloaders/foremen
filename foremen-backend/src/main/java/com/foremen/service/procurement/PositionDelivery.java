package com.foremen.service.procurement;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * FOR-11-06 (R10.4): the single definition of {@code Delivery_Complete}:
 * {@code status = RECEIVED AND NOT client_unpaid}. A received position that the client has not paid
 * for (a buyer-added position) is delivered, but not complete.
 */
public final class PositionDelivery {

    private PositionDelivery() {
    }

    public static boolean isComplete(PositionStatus status, boolean clientUnpaid) {
        return status == PositionStatus.RECEIVED && !clientUnpaid;
    }

    /** JPA mirror of {@link #isComplete}. */
    public static Predicate completePredicate(Root<PurchasePositionEntity> root, CriteriaBuilder cb) {
        return cb.and(cb.equal(root.get("status"), PositionStatus.RECEIVED),
                cb.isFalse(root.<Boolean>get("clientUnpaid")));
    }
}
