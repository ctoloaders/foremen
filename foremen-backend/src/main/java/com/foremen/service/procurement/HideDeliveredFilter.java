package com.foremen.service.procurement;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchaseRequestEntity;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * FOR-11-00 (R22): the {@code hideDelivered} list filter for purchase positions and purchase requests.
 *
 * <p>The flag is read from the current HTTP request ({@value #PARAM}, default {@code false}), so the
 * generic list/extended/count entry points need no signature change. Outside a web request (internal
 * callers, schedulers) the filter is never applied. The returned specifications are AND-ed with the
 * user filter and the project scope by {@code buildFinalSpecification}, so totals and pagination stay
 * correct.
 *
 * <p>FOR-11-06 (R10.4): only {@code Delivery_Complete} ({@link PositionDelivery}) is hidden; a received
 * but client-unpaid position stays visible.
 */
public final class HideDeliveredFilter {

    public static final String PARAM = "hideDelivered";

    private HideDeliveredFilter() {
    }

    /** {@code true} when the current HTTP request carries {@code hideDelivered=true}. */
    public static boolean requested() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servletAttrs)) {
            return false;
        }
        return Boolean.parseBoolean(servletAttrs.getRequest().getParameter(PARAM));
    }

    /**
     * Positions: excludes {@code Delivery_Complete}; {@code PARTIALLY_RECEIVED} and received-unpaid stay
     * visible (R22.1, FOR-11-06 R10.4).
     */
    public static Specification<PurchasePositionEntity> positions() {
        return (root, query, cb) -> cb.not(PositionDelivery.completePredicate(root, cb));
    }

    /**
     * Requests: excludes derived status {@code COMPLETED}, i.e. at least one {@code Delivery_Complete}
     * position and no position that is neither complete nor {@code CANCELLED}. Mirrors
     * {@link PurchaseRequestStatusDeriver} (R22.2); a request without positions stays visible.
     */
    public static Specification<PurchaseRequestEntity> requests() {
        return (root, query, cb) -> {
            Subquery<Long> received = query.subquery(Long.class);
            Root<PurchasePositionEntity> r = received.from(PurchasePositionEntity.class);
            received.select(r.get("id"))
                    .where(cb.equal(r.get("purchaseRequest"), root),
                            PositionDelivery.completePredicate(r, cb));
            Subquery<Long> open = query.subquery(Long.class);
            Root<PurchasePositionEntity> o = open.from(PurchasePositionEntity.class);
            open.select(o.get("id"))
                    .where(cb.equal(o.get("purchaseRequest"), root),
                            cb.not(PositionDelivery.completePredicate(o, cb)),
                            cb.notEqual(o.get("status"), PositionStatus.CANCELLED));
            Predicate completed = cb.and(cb.exists(received), cb.not(cb.exists(open)));
            return cb.not(completed);
        };
    }
}
