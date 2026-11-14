package com.foremen.service.procurement.planner;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.dao.model.RequestStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.estimate.margins.Margin;
import com.foremen.service.estimate.margins.MarginCostService;
import com.foremen.service.procurement.PurchaseRequestOverdue;
import com.foremen.service.procurement.PurchaseRequestStatusDeriver;
import com.foremen.service.procurement.position.PositionAudienceResolver;
import com.foremen.service.procurement.position.PositionMoney;
import com.foremen.service.procurement.position.PositionPriceVisibility;
import com.foremen.service.procurement.position.PositionPriceVisibility.Audience;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * Groups of the purchase planner (FOR-11-01 R3, R4.5). Groups are keyed by internal purchase request
 * (or one synthetic group). The groups endpoint never returns rows; rows are loaded lazily through the
 * planner endpoint with {@code requestId} / {@code noRequest}.
 *
 * <p>The filter is the one {@link PlannerQueryService#specification} composes for the planner, so group
 * counts and totals cover exactly the filtered rows. Totals are summed in Java with
 * {@link PositionMoneyMath} / {@link PositionMarginCalculator}, so they use the row rounding, and the
 * sum of the group totals equals the totals of the flat list.
 */
@Service
public class PlannerGroupService {

    static final String GROUP_BY_INVALID = "error.purchasePlanner.groupBy.invalid";
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final String KEY_NO_REQUEST = "NO_REQUEST";
    static final String KEY_ALL = "ALL";

    /** Allowed grouping modes; the external-order / proforma modes are reserved for FOR-11-03. */
    public enum GroupBy { REQUEST, NONE }

    /** Group header with totals; {@code totals} is {@code null} for non-STAFF_FULL callers. */
    public record PlannerGroupDto(
            String key,
            Long requestId,
            String referenceNumber,
            String referenceLink,
            LocalDate expectedDeliveryDate,
            Long assignedBuyerId,
            String assignedBuyerName,
            RequestStatus status,
            boolean overdue,
            long positionCount,
            Totals totals) {
    }

    /** R4.5 totals over the non-cancelled positions of a group. */
    public record Totals(
            BigDecimal saleNet,
            BigDecimal saleGross,
            BigDecimal costNet,
            BigDecimal deliveryCost,
            BigDecimal marginAmount,
            BigDecimal marginPct,
            boolean marginIncomplete) {
    }

    /** The visible money of one position, input of {@link #aggregate}. */
    record Line(BigDecimal quantity, BigDecimal salePrice, BigDecimal saleVatRate, BigDecimal actualPurchasePrice,
                BigDecimal plannedPurchasePrice, BigDecimal deliveryCostShare) {
    }

    private final EntityManager entityManager;
    private final PlannerQueryService queryService;
    private final PositionAudienceResolver audienceResolver;
    private final Clock clock;

    public PlannerGroupService(EntityManager entityManager, PlannerQueryService queryService,
                               PositionAudienceResolver audienceResolver, Clock clock) {
        this.entityManager = entityManager;
        this.queryService = queryService;
        this.audienceResolver = audienceResolver;
        this.clock = clock;
    }

    /** {@code null}/blank means {@code REQUEST}; anything else than the allowed values is a 400. */
    public static GroupBy parseGroupBy(String groupBy) {
        if (groupBy == null || groupBy.isBlank()) {
            return GroupBy.REQUEST;
        }
        try {
            return GroupBy.valueOf(groupBy.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, GROUP_BY_INVALID);
        }
    }

    /**
     * One page of groups ordered by the earliest site-delivery date (nulls last), then request id
     * (the synthetic group last).
     */
    @Transactional(readOnly = true)
    public Page<PlannerGroupDto> groups(PlannerFilter filter, GroupBy groupBy, Pageable pageable,
                                        Authentication auth) {
        Specification<PurchasePositionEntity> spec = queryService.specification(filter, auth);
        List<GroupKey> all = groupKeys(spec, groupBy);
        int size = pageable == null || pageable.isUnpaged() ? DEFAULT_PAGE_SIZE
                : Math.min(Math.max(pageable.getPageSize(), 1), MAX_PAGE_SIZE);
        int number = pageable == null || pageable.isUnpaged() ? 0 : pageable.getPageNumber();
        Pageable result = PageRequest.of(number, size);
        long offset = (long) number * size;
        if (offset >= all.size()) {
            return new PageImpl<>(List.of(), result, all.size());
        }
        List<GroupKey> pageKeys = all.subList((int) offset, (int) Math.min(all.size(), offset + size));

        Audience audience = audienceResolver.resolve(auth);
        Map<String, Totals> totals = audience == Audience.STAFF_FULL
                ? totals(spec, groupBy, pageKeys) : Map.of();
        Map<Long, PurchaseRequestEntity> requests = requests(pageKeys);
        Map<Long, RequestStatus> statuses = statuses(requests.keySet());
        PurchaseRequestOverdue overdue = new PurchaseRequestOverdue(clock);

        List<PlannerGroupDto> content = new ArrayList<>(pageKeys.size());
        for (GroupKey key : pageKeys) {
            String keyString = key.key(groupBy);
            Totals t = audience == Audience.STAFF_FULL ? totals.getOrDefault(keyString, emptyTotals()) : null;
            PurchaseRequestEntity request = key.requestId() == null ? null : requests.get(key.requestId());
            if (request == null) {
                content.add(new PlannerGroupDto(keyString, null, null, null, null, null, null, null, false,
                        key.count(), t));
                continue;
            }
            RequestStatus status = statuses.getOrDefault(request.getId(), RequestStatus.OPEN);
            content.add(new PlannerGroupDto(keyString, request.getId(), request.getReferenceNumber(),
                    request.getReferenceLink(), request.getExpectedDeliveryDate(),
                    request.getAssignedBuyer() == null ? null : request.getAssignedBuyer().getId(),
                    request.getAssignedBuyer() == null ? null : request.getAssignedBuyer().getName(),
                    status, overdue.isOverdue(request.getExpectedDeliveryDate(), status), key.count(), t));
        }
        return new PageImpl<>(content, result, all.size());
    }

    // ---------------------------------------------------------------- queries

    private record GroupKey(Long requestId, LocalDate earliest, long count) {
        String key(GroupBy groupBy) {
            if (groupBy == GroupBy.NONE) {
                return KEY_ALL;
            }
            return requestId == null ? KEY_NO_REQUEST : String.valueOf(requestId);
        }
    }

    /** Query 1: every group (request id, earliest site-delivery date, count) of the filtered set, ordered. */
    private List<GroupKey> groupKeys(Specification<PurchasePositionEntity> spec, GroupBy groupBy) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<PurchasePositionEntity> root = query.from(PurchasePositionEntity.class);
        Expression<LocalDate> site = cb.coalesce(root.<LocalDate>get("plannedDeliveryDate"),
                root.<LocalDate>get("needByDate"));
        Predicate where = spec.toPredicate(root, query, cb);
        List<GroupKey> keys = new ArrayList<>();
        if (groupBy == GroupBy.NONE) {
            query.multiselect(cb.least(site).alias("earliest"), cb.count(root).alias("cnt")).where(where);
            Tuple t = entityManager.createQuery(query).getSingleResult();
            long count = ((Number) t.get("cnt")).longValue();
            if (count > 0) {
                keys.add(new GroupKey(null, t.get("earliest", LocalDate.class), count));
            }
            return keys;
        }
        Expression<Long> requestId = root.get("purchaseRequest").get("id");
        query.multiselect(requestId.alias("rid"), cb.least(site).alias("earliest"), cb.count(root).alias("cnt"))
                .where(where)
                .groupBy(requestId);
        for (Tuple t : entityManager.createQuery(query).getResultList()) {
            keys.add(new GroupKey(t.get("rid", Long.class), t.get("earliest", LocalDate.class),
                    ((Number) t.get("cnt")).longValue()));
        }
        keys.sort(Comparator
                .comparing(GroupKey::earliest, Comparator.nullsLast(Comparator.<LocalDate>naturalOrder()))
                .thenComparing(GroupKey::requestId, Comparator.nullsLast(Comparator.<Long>naturalOrder())));
        return keys;
    }

    /** Query 2a: request headers of the page. */
    private Map<Long, PurchaseRequestEntity> requests(List<GroupKey> keys) {
        List<Long> ids = keys.stream().map(GroupKey::requestId).filter(java.util.Objects::nonNull).toList();
        Map<Long, PurchaseRequestEntity> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        List<PurchaseRequestEntity> list = entityManager.createQuery(
                "select r from PurchaseRequestEntity r left join fetch r.assignedBuyer where r.id in :ids",
                PurchaseRequestEntity.class).setParameter("ids", ids).getResultList();
        for (PurchaseRequestEntity r : list) {
            map.put(r.getId(), r);
        }
        return map;
    }

    /** Query 2b: derived status over <b>all</b> positions of each request, not only the filtered ones. */
    private Map<Long, RequestStatus> statuses(Set<Long> requestIds) {
        Map<Long, RequestStatus> result = new HashMap<>();
        if (requestIds.isEmpty()) {
            return result;
        }
        Map<Long, List<PositionStatus>> stages = new HashMap<>();
        List<Object[]> rows = entityManager.createQuery(
                "select p.purchaseRequest.id, p.status from PurchasePositionEntity p "
                        + "where p.purchaseRequest.id in :ids", Object[].class)
                .setParameter("ids", requestIds).getResultList();
        for (Object[] row : rows) {
            stages.computeIfAbsent((Long) row[0], k -> new ArrayList<>()).add((PositionStatus) row[1]);
        }
        for (Long id : requestIds) {
            result.put(id, PurchaseRequestStatusDeriver.derive(stages.getOrDefault(id, List.of())));
        }
        return result;
    }

    /** Query 3: slim projection of the filtered, non-cancelled positions of the page's groups. */
    private Map<String, Totals> totals(Specification<PurchasePositionEntity> spec, GroupBy groupBy,
                                       List<GroupKey> pageKeys) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<PurchasePositionEntity> root = query.from(PurchasePositionEntity.class);
        List<Predicate> where = new ArrayList<>();
        where.add(spec.toPredicate(root, query, cb));
        where.add(cb.notEqual(root.get("status"), PositionStatus.CANCELLED));
        if (groupBy == GroupBy.REQUEST) {
            List<Long> ids = pageKeys.stream().map(GroupKey::requestId).filter(java.util.Objects::nonNull).toList();
            boolean hasNoRequest = pageKeys.stream().anyMatch(k -> k.requestId() == null);
            List<Predicate> any = new ArrayList<>();
            if (!ids.isEmpty()) {
                any.add(root.get("purchaseRequest").get("id").in(ids));
            }
            if (hasNoRequest) {
                any.add(cb.isNull(root.get("purchaseRequest")));
            }
            where.add(cb.or(any.toArray(new Predicate[0])));
        }
        var saleVat = root.join("saleVatRate", JoinType.LEFT);
        query.multiselect(
                root.get("purchaseRequest").get("id").alias("rid"),
                root.get("sourcingPath").alias("path"),
                root.get("status").alias("status"),
                root.get("quantity").alias("qty"),
                root.get("salePrice").alias("sale"),
                saleVat.get("rate").alias("saleRate"),
                root.get("actualPurchasePrice").alias("actual"),
                root.get("plannedPurchasePrice").alias("planned"),
                root.get("deliveryCostShare").alias("delivery"))
                .where(where.toArray(new Predicate[0]));

        Map<String, List<Line>> lines = new LinkedHashMap<>();
        for (Tuple t : entityManager.createQuery(query).getResultList()) {
            PositionMoney visible = PositionPriceVisibility.apply(Audience.STAFF_FULL,
                    t.get("path", com.foremen.dao.model.SourcingPath.class),
                    t.get("status", PositionStatus.class),
                    new PositionMoney(t.get("planned", BigDecimal.class), t.get("actual", BigDecimal.class), null,
                            t.get("sale", BigDecimal.class), null, t.get("delivery", BigDecimal.class)));
            Long rid = t.get("rid", Long.class);
            String key = groupBy == GroupBy.NONE ? KEY_ALL : rid == null ? KEY_NO_REQUEST : String.valueOf(rid);
            lines.computeIfAbsent(key, k -> new ArrayList<>()).add(new Line(t.get("qty", BigDecimal.class),
                    visible.salePrice(), t.get("saleRate", BigDecimal.class), visible.actualPurchasePrice(),
                    visible.plannedPurchasePrice(), visible.deliveryCostShare()));
        }
        Map<String, Totals> result = new HashMap<>();
        lines.forEach((k, v) -> result.put(k, aggregate(v)));
        return result;
    }

    // ------------------------------------------------------------ aggregation

    static Totals emptyTotals() {
        return aggregate(List.of());
    }

    /**
     * R4.5: sums of the rounded lines. Positions whose margin is unavailable are skipped in the margin
     * sum and set {@code marginIncomplete}; the percentage is margin over the revenue of the counted
     * positions ({@code null} when that revenue is zero or nothing was counted).
     */
    static Totals aggregate(List<Line> lines) {
        List<PositionMoneyMath.Line> saleLines = new ArrayList<>();
        BigDecimal costNet = BigDecimal.ZERO.setScale(2);
        BigDecimal delivery = BigDecimal.ZERO.setScale(2);
        BigDecimal revenue = BigDecimal.ZERO.setScale(2);
        BigDecimal cost = BigDecimal.ZERO.setScale(2);
        int counted = 0;
        boolean incomplete = false;
        for (Line l : lines) {
            saleLines.add(PositionMoneyMath.saleLine(l.salePrice(), l.quantity(), l.saleVatRate()));
            BigDecimal purchaseUnit = l.actualPurchasePrice() != null
                    ? l.actualPurchasePrice() : l.plannedPurchasePrice();
            BigDecimal purchaseNet = PositionMoneyMath.purchaseNet(purchaseUnit, l.quantity());
            if (purchaseNet != null) {
                costNet = costNet.add(purchaseNet);
            }
            if (l.deliveryCostShare() != null) {
                delivery = delivery.add(l.deliveryCostShare());
            }
            Margin m = PositionMarginCalculator.margin(l.salePrice(), l.actualPurchasePrice(),
                    l.plannedPurchasePrice(), l.quantity(), l.deliveryCostShare());
            PositionMoneyMath.Line sale = PositionMoneyMath.saleLine(l.salePrice(), l.quantity(), null);
            if (m.amount() == null || sale == null) {
                incomplete = true;
                continue;
            }
            counted++;
            revenue = revenue.add(sale.net());
            cost = cost.add(sale.net().subtract(m.amount()));
        }
        PositionMoneyMath.Totals sum = PositionMoneyMath.sum(saleLines);
        Margin margin = counted == 0 ? Margin.UNAVAILABLE : MarginCostService.materialMargin(revenue, cost);
        return new Totals(sum.net(), sum.gross(), costNet, delivery, margin.amount(), margin.pct(), incomplete);
    }
}
