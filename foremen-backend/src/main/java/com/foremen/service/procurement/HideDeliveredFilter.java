package com.foremen.service.procurement;

import java.util.List;

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

    /** Positions: excludes stage {@code RECEIVED}; {@code PARTIALLY_RECEIVED} stays visible (R22.1). */
    public static Specification<PurchasePositionEntity> positions() {
        return (root, query, cb) -> cb.notEqual(root.get("status"), PositionStatus.RECEIVED);
    }

    /**
     * Requests: excludes derived status {@code COMPLETED}, i.e. at least one {@code RECEIVED} position
     * and no position outside {RECEIVED, CANCELLED}. Mirrors {@link PurchaseRequestStatusDeriver}
     * (R22.2); a request without positions stays visible.
     */
    public static Specification<PurchaseRequestEntity> requests() {
        return (root, query, cb) -> {
            Subquery<Long> received = query.subquery(Long.class);
            Root<PurchasePositionEntity> r = received.from(PurchasePositionEntity.class);
            received.select(r.get("id"))
                    .where(cb.equal(r.get("purchaseRequest"), root),
                            cb.equal(r.get("status"), PositionStatus.RECEIVED));

            Subquery<Long> open = query.subquery(Long.class);
            Root<PurchasePositionEntity> o = open.from(PurchasePositionEntity.class);
            open.select(o.get("id"))
                    .where(cb.equal(o.get("purchaseRequest"), root),
                            cb.not(o.get("status").in(List.of(PositionStatus.RECEIVED, PositionStatus.CANCELLED))));

            Predicate completed = cb.and(cb.exists(received), cb.not(cb.exists(open)));
            return cb.not(completed);
        };
    }
}
