package com.foremen.service.procurement.planner;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.planner.PlannerFilter.BuyerMode;
import com.foremen.service.procurement.planner.PlannerSliceRules.PositionSnapshot;
import com.foremen.service.procurement.planner.PlannerSliceRules.Slice;
import com.foremen.service.procurement.position.PositionAudienceResolver;
import com.foremen.service.procurement.position.PositionMoney;
import com.foremen.service.procurement.position.PositionPriceVisibility;
import com.foremen.service.procurement.position.PositionPriceVisibility.Audience;
import com.foremen.service.procurement.position.PositionStage;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;

/**
 * BUYER board (FOR-11-01 R8): six fixed stage columns with counts and the first {@code limit} cards of
 * each, ordered by site-delivery date ascending (nulls last, then id).
 *
 * <p>The filter reuses {@link PlannerQueryService#specification} so the scope and the filters are
 * identical to the planner; the stage restriction ({@code WITH_BUYER..RECEIVED}) keeps positions that
 * have not passed the gate out of the board. One grouped count statement plus one {@code LIMIT} query
 * per column (the design fallback to the window function; constant 7 statements). Money leaves only
 * through {@link PositionPriceVisibility}.
 */
@Service
public class BoardService {

    static final String LIMIT_INVALID = "error.purchasePlanner.board.limit.invalid";
    public static final int DEFAULT_LIMIT = 30;
    public static final int MAX_LIMIT = 100;

    /** The fixed column order (R8.2). */
    public static final List<PositionStatus> COLUMNS = List.of(
            PositionStatus.WITH_BUYER, PositionStatus.ORDERED, PositionStatus.SUPPLIER_PAID,
            PositionStatus.IN_DELIVERY, PositionStatus.PARTIALLY_RECEIVED, PositionStatus.RECEIVED);

    private final EntityManager entityManager;
    private final PlannerQueryService plannerQueryService;
    private final PositionAudienceResolver audienceResolver;
    private final PlannerSliceRules sliceRules;

    public BoardService(EntityManager entityManager,
                        PlannerQueryService plannerQueryService,
                        PositionAudienceResolver audienceResolver,
                        PlannerSliceRules sliceRules) {
        this.entityManager = entityManager;
        this.plannerQueryService = plannerQueryService;
        this.audienceResolver = audienceResolver;
        this.sliceRules = sliceRules;
    }

    /** One board column. {@code cards} is empty for {@code RECEIVED} when delivered positions are hidden. */
    public record Column(PositionStatus status, String statusCode, long count, List<Card> cards,
                         boolean hasMore) {
    }

    public record Board(List<Column> columns) {
    }

    /** A card (R8.4). {@code money} is {@code null} for every audience except {@code STAFF_FULL}. */
    public record Card(Long positionId, Long projectId, String projectName, Material material,
                       String sellerName, BigDecimal quantity, String unitName, Long requestId,
                       String requestReferenceNumber, LocalDate siteDeliveryDate, boolean late,
                       boolean deliveryRisk, LocalDate orderByDate, SourcingPath sourcingPath, Money money) {
    }

    /** Material label of a card: model, producer and photo. */
    public record Material(String model, String producerName, String photo) {
    }

    /** The Money subset of a card. */
    public record Money(BigDecimal salePrice, BigDecimal plannedPurchasePrice,
                        BigDecimal actualPurchasePrice, boolean overCeiling) {
    }

    @Transactional(readOnly = true)
    public Board board(Long projectId, BuyerMode buyer, String q, boolean hideDelivered, Integer limit,
                       Authentication auth) {
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, LIMIT_INVALID);
        }
        // hideDelivered is handled per column (RECEIVED keeps its count), not in the shared filter
        PlannerFilter filter = new PlannerFilter(projectId, java.util.Set.copyOf(COLUMNS), null, null, false,
                buyer == null ? BuyerMode.ALL : buyer, Slice.ALL, false, q, false);
        Specification<PurchasePositionEntity> spec = plannerQueryService.specification(filter, auth);
        Map<PositionStatus, Long> counts = counts(spec);

        Audience audience = audienceResolver.resolve(auth);
        LocalDate today = sliceRules.today();
        boolean ru = isRussian();
        List<Column> columns = new ArrayList<>(COLUMNS.size());
        for (PositionStatus status : COLUMNS) {
            long count = counts.getOrDefault(status, 0L);
            boolean hidden = hideDelivered && status == PositionStatus.RECEIVED;
            List<Card> cards = List.of();
            if (!hidden && count > 0) {
                cards = cards(spec, status, size, audience, today, ru);
            }
            columns.add(new Column(status, PositionStage.statusCode(status), count, cards,
                    !hidden && count > cards.size()));
        }
        return new Board(List.copyOf(columns));
    }

    private Map<PositionStatus, Long> counts(Specification<PurchasePositionEntity> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<PurchasePositionEntity> root = query.from(PurchasePositionEntity.class);
        query.multiselect(root.get("status").alias("status"), cb.count(root).alias("n"))
                .where(spec.toPredicate(root, query, cb))
                .groupBy(root.get("status"));
        Map<PositionStatus, Long> result = new EnumMap<>(PositionStatus.class);
        for (Tuple t : entityManager.createQuery(query).getResultList()) {
            result.put(t.get("status", PositionStatus.class), t.get("n", Long.class));
        }
        return result;
    }

    private List<Card> cards(Specification<PurchasePositionEntity> spec, PositionStatus status, int limit,
                             Audience audience, LocalDate today, boolean ru) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<PurchasePositionEntity> query = cb.createQuery(PurchasePositionEntity.class);
        Root<PurchasePositionEntity> root = query.from(PurchasePositionEntity.class);
        root.fetch("project", JoinType.INNER);
        root.fetch("unit", JoinType.INNER);
        root.fetch("purchaseRequest", JoinType.LEFT);
        for (String attribute : List.of("plannedMaterial", "actualMaterial")) {
            var material = root.fetch(attribute, JoinType.LEFT);
            material.fetch("producer", JoinType.LEFT);
            material.fetch("seller", JoinType.LEFT);
        }
        Expression<LocalDate> site = cb.coalesce(root.<LocalDate>get("plannedDeliveryDate"),
                root.<LocalDate>get("needByDate"));
        query.select(root)
                .where(cb.and(spec.toPredicate(root, query, cb), cb.equal(root.get("status"), status)))
                .orderBy(cb.asc(cb.<Integer>selectCase().when(cb.isNull(site), 1).otherwise(0)),
                        cb.asc(site), cb.asc(root.get("id")));
        List<PurchasePositionEntity> entities = entityManager.createQuery(query).setMaxResults(limit)
                .getResultList();
        java.util.Set<Long> risk = plannerQueryService.deliveryRiskIds(entities);
        List<Card> cards = new ArrayList<>(entities.size());
        for (PurchasePositionEntity p : entities) {
            cards.add(card(p, audience, today, ru, risk.contains(p.getId())));
        }
        return List.copyOf(cards);
    }

    private static Card card(PurchasePositionEntity p, Audience audience, LocalDate today, boolean ru,
                             boolean deliveryRisk) {
        PositionSnapshot snapshot = new PositionSnapshot(p.getSourcingPath(), p.getStatus(), p.getNeedByDate(),
                p.getOrderByDate(), p.getPlannedDeliveryDate(), 0);
        FinishingMaterialEntity effective = p.getActualMaterial() != null ? p.getActualMaterial()
                : p.getPlannedMaterial();
        MaterialProducerEntity producer = effective == null ? null : effective.getProducer();
        MaterialSellerEntity seller = effective == null ? null : effective.getSeller();
        PurchaseRequestEntity request = p.getPurchaseRequest();
        LocalDate siteDate = PlannerSliceRules.siteDeliveryDate(snapshot);
        return new Card(
                p.getId(),
                p.getProject().getId(),
                p.getProject().getName(),
                new Material(effective == null ? null : effective.getModel(),
                        producer == null ? null : ru ? producer.getNameRU() : producer.getNamePL(),
                        effective == null ? null : effective.getPhoto()),
                seller == null ? null : ru ? seller.getNameRU() : seller.getNamePL(),
                p.getQuantity(),
                ru ? p.getUnit().getNameRU() : p.getUnit().getNamePL(),
                request == null ? null : request.getId(),
                request == null ? null : request.getReferenceNumber(),
                siteDate,
                PlannerSliceRules.isLate(snapshot, today),
                deliveryRisk,
                p.getOrderByDate(),
                p.getSourcingPath(),
                money(p, audience));
    }

    private static Money money(PurchasePositionEntity p, Audience audience) {
        if (audience != Audience.STAFF_FULL) {
            return null;
        }
        PositionMoney raw = new PositionMoney(p.getPlannedPurchasePrice(), p.getActualPurchasePrice(),
                p.getPurchaseVatRate() == null ? null : p.getPurchaseVatRate().getId(), p.getSalePrice(),
                p.getSaleVatRate() == null ? null : p.getSaleVatRate().getId(), p.getDeliveryCostShare());
        PositionMoney visible = PositionPriceVisibility.apply(audience, p.getSourcingPath(), p.getStatus(), raw);
        boolean over = visible.actualPurchasePrice() != null && visible.salePrice() != null
                && visible.actualPurchasePrice().compareTo(visible.salePrice()) > 0;
        return new Money(visible.salePrice(), visible.plannedPurchasePrice(), visible.actualPurchasePrice(), over);
    }

    private static boolean isRussian() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
