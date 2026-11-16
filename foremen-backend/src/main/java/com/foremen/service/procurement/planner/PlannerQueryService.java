package com.foremen.service.procurement.planner;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.config.procurement.ProcurementProperties;
import com.foremen.dao.PurchasePositionVariantDao;
import com.foremen.dao.model.ClientProformaLineEntity;
import com.foremen.dao.model.ExternalOrderLineEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.estimate.margins.Margin;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import com.foremen.service.procurement.HideDeliveredFilter;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.planner.PlannerFilter.BuyerMode;
import com.foremen.service.procurement.planner.PlannerRowDto.Action;
import com.foremen.service.procurement.planner.PlannerRowDto.Material;
import com.foremen.service.procurement.planner.PlannerRowDto.Money;
import com.foremen.service.procurement.planner.PlannerRowDto.WorkCategoryCandidate;
import com.foremen.service.procurement.planner.PlannerSliceRules.PositionSnapshot;
import com.foremen.service.procurement.planner.PlannerSliceRules.Slice;
import com.foremen.service.procurement.planner.WorkCategoryResolver.Resolution;
import com.foremen.service.procurement.planner.WorkCategoryResolver.Scheduled;
import com.foremen.service.procurement.planner.WorkCategoryResolver.Source;
import com.foremen.service.procurement.planner.WorkStartResolver.ProjectCategory;
import com.foremen.service.procurement.externalorder.ExternalOrderStatus;
import com.foremen.service.procurement.proforma.ProformaStatus;
import com.foremen.service.procurement.position.PositionAudienceResolver;
import com.foremen.service.procurement.position.PositionMoney;
import com.foremen.service.procurement.position.PositionPriceVisibility;
import com.foremen.service.procurement.position.PositionPriceVisibility.Audience;
import com.foremen.service.procurement.position.PositionStage;
import com.foremen.service.procurement.position.PositionTransitionPolicy;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Decision;
import com.foremen.service.procurement.position.VariantStagePolicy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Fetch;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Read model of the purchase planner (FOR-11-01 R1, R2, R4.4, R5.6-5.8, R9.2, R9.5).
 *
 * <p>The filter is composed as one {@link Specification} (scope, filters, slice/urgent predicates from
 * {@link PlannerSliceRules}, the reused {@link HideDeliveredFilter}, escaped {@code q}) and applied in
 * the database, so pages and counts agree. Rows are assembled in batches: one page query with fetch
 * joins, one variant-count query, and per distinct project / type a fixed number of lookups for the
 * work-category link, never one query per row. Money leaves only through
 * {@link PositionPriceVisibility}; there is no money filter or sort.
 */
@Service
public class PlannerQueryService {

    static final String SORT_INVALID = "error.purchasePlanner.sort.invalid";
    static final String RESOURCE = "PURCHASE_POSITIONS";
    static final int MAX_PAGE_SIZE = 100;

    private static final Set<String> SORT_FIELDS = Set.of(
            "siteDeliveryDate", "needByDate", "orderByDate", "decisionDeadline", "createdDate", "status");

    private final EntityManager entityManager;
    private final ProcurementProjectAccess projectAccess;
    private final PositionAudienceResolver audienceResolver;
    private final PlannerSliceRules sliceRules;
    private final ProcurementProperties properties;
    private final PositionTransitionPolicy transitionPolicy;
    private final PurchasePositionVariantDao variantDao;
    private final ScheduledCategoryService scheduledCategoryService;
    private final WorkConsumptionLookup consumptionLookup;
    private final WorkStartResolver workStartResolver;
    private final ForemenPermissionEvaluator permissionEvaluator;
    private final WorkCategoryResolver categoryResolver = new WorkCategoryResolver();

    public PlannerQueryService(EntityManager entityManager,
                               ProcurementProjectAccess projectAccess,
                               PositionAudienceResolver audienceResolver,
                               PlannerSliceRules sliceRules,
                               ProcurementProperties properties,
                               PositionTransitionPolicy transitionPolicy,
                               PurchasePositionVariantDao variantDao,
                               ScheduledCategoryService scheduledCategoryService,
                               WorkConsumptionLookup consumptionLookup,
                               WorkStartResolver workStartResolver,
                               ForemenPermissionEvaluator permissionEvaluator) {
        this.entityManager = entityManager;
        this.projectAccess = projectAccess;
        this.audienceResolver = audienceResolver;
        this.sliceRules = sliceRules;
        this.properties = properties;
        this.transitionPolicy = transitionPolicy;
        this.variantDao = variantDao;
        this.scheduledCategoryService = scheduledCategoryService;
        this.consumptionLookup = consumptionLookup;
        this.workStartResolver = workStartResolver;
        this.permissionEvaluator = permissionEvaluator;
    }

    // ------------------------------------------------------------------ page

    /**
     * One page of planner rows. The sort comes from the first order of {@code pageable} (default
     * {@code siteDeliveryDate} ascending, nulls last, then id); an unknown field is a 400.
     */
    @Transactional(readOnly = true)
    public Page<PlannerRowDto> plan(PlannerFilter filter, Pageable pageable, Authentication auth) {
        PlannerFilter f = filter == null ? PlannerFilter.none() : filter;
        Sort.Order sortOrder = sortOrder(pageable);
        Scope scope = scope(auth);
        if (scope.isEmpty()) {
            return new PageImpl<>(List.of(), pageable == null ? Pageable.unpaged() : pageable, 0);
        }
        Specification<PurchasePositionEntity> spec = specification(f, auth, scope);
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<PurchasePositionEntity> countRoot = countQuery.from(PurchasePositionEntity.class);
        countQuery.select(cb.count(countRoot)).where(spec.toPredicate(countRoot, countQuery, cb));
        long total = entityManager.createQuery(countQuery).getSingleResult();

        int size = pageable == null || pageable.isUnpaged() ? MAX_PAGE_SIZE
                : Math.min(pageable.getPageSize(), MAX_PAGE_SIZE);
        long offset = pageable == null || pageable.isUnpaged() ? 0 : (long) pageable.getPageNumber() * size;
        List<PurchasePositionEntity> entities = List.of();
        if (total > offset) {
            CriteriaQuery<PurchasePositionEntity> dataQuery = cb.createQuery(PurchasePositionEntity.class);
            Root<PurchasePositionEntity> root = dataQuery.from(PurchasePositionEntity.class);
            fetchAssociations(root);
            dataQuery.select(root)
                    .where(spec.toPredicate(root, dataQuery, cb))
                    .orderBy(orders(root, cb, sortOrder));
            entities = entityManager.createQuery(dataQuery)
                    .setFirstResult((int) offset)
                    .setMaxResults(size)
                    .getResultList();
        }
        List<PlannerRowDto> rows = assemble(entities, auth);
        Pageable result = pageable == null || pageable.isUnpaged()
                ? Pageable.unpaged()
                : org.springframework.data.domain.PageRequest.of(pageable.getPageNumber(), size,
                        pageable.getSort());
        return new PageImpl<>(rows, result, total);
    }

    /**
     * Count per slice over the same filters except the slice itself (R2.6), in one statement of six
     * {@code sum(case when ...)} expressions. Every {@link Slice} is present in the result.
     */
    @Transactional(readOnly = true)
    public Map<Slice, Long> sliceCounts(PlannerFilter filter, Authentication auth) {
        PlannerFilter f = (filter == null ? PlannerFilter.none() : filter).withoutSlice();
        Map<Slice, Long> result = new EnumMap<>(Slice.class);
        for (Slice slice : Slice.values()) {
            result.put(slice, 0L);
        }
        Scope scope = scope(auth);
        if (scope.isEmpty()) {
            return result;
        }
        Specification<PurchasePositionEntity> spec = specification(f, auth, scope);
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<PurchasePositionEntity> root = query.from(PurchasePositionEntity.class);
        LocalDate today = sliceRules.today();
        List<jakarta.persistence.criteria.Selection<?>> selections = new ArrayList<>();
        for (Slice slice : Slice.values()) {
            Predicate p = PlannerSliceRules.slicePredicate(slice, root, query, cb, today);
            selections.add(cb.sum(cb.<Long>selectCase().when(p, 1L).otherwise(0L)).alias(slice.name()));
        }
        query.multiselect(selections).where(spec.toPredicate(root, query, cb));
        Tuple tuple = entityManager.createQuery(query).getSingleResult();
        for (Slice slice : Slice.values()) {
            Object value = tuple.get(slice.name());
            result.put(slice, value instanceof Number n ? n.longValue() : 0L);
        }
        return result;
    }

    // ---------------------------------------------------------- specification

    /**
     * The composed filter for the caller. Reused by the groups and board services so every consumer
     * applies identical scope and predicates.
     */
    public Specification<PurchasePositionEntity> specification(PlannerFilter filter, Authentication auth) {
        return specification(filter == null ? PlannerFilter.none() : filter, auth, scope(auth));
    }

    private Specification<PurchasePositionEntity> specification(PlannerFilter f, Authentication auth,
                                                               Scope scope) {
        LocalDate today = sliceRules.today();
        int urgentDays = properties.urgentDays();
        Long me = currentUserId(auth);
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (scope.isEmpty()) {
                where.add(cb.disjunction());
            } else if (!scope.all()) {
                where.add(root.get("project").get("id").in(scope.projectIds()));
            }
            if (f.projectId() != null) {
                where.add(cb.equal(root.get("project").get("id"), f.projectId()));
            }
            if (f.statuses() != null && !f.statuses().isEmpty()) {
                where.add(root.get("status").in(f.statuses()));
            }
            if (f.sourcingPath() != null) {
                where.add(cb.equal(root.get("sourcingPath"), f.sourcingPath()));
            }
            if (f.requestId() != null) {
                where.add(cb.equal(root.get("purchaseRequest").get("id"), f.requestId()));
            }
            if (f.noRequest()) {
                where.add(cb.isNull(root.get("purchaseRequest")));
            }
            if (f.proformaId() != null) {
                Subquery<Long> lines = query.subquery(Long.class);
                Root<ClientProformaLineEntity> line = lines.from(ClientProformaLineEntity.class);
                lines.select(line.<Long>get("id")).where(
                        cb.equal(line.get("position"), root),
                        cb.isTrue(line.<Boolean>get("active")),
                        cb.equal(line.get("proforma").get("id"), f.proformaId()));
                where.add(cb.exists(lines));
            }
            if (f.externalOrderId() != null) {
                Subquery<Long> lines = query.subquery(Long.class);
                Root<ExternalOrderLineEntity> line = lines.from(ExternalOrderLineEntity.class);
                lines.select(line.<Long>get("id")).where(
                        cb.equal(line.get("position"), root),
                        cb.equal(line.get("externalOrder").get("id"), f.externalOrderId()));
                where.add(cb.exists(lines));
            }
            if (f.noProforma()) {
                Subquery<Long> lines = query.subquery(Long.class);
                Root<ClientProformaLineEntity> line = lines.from(ClientProformaLineEntity.class);
                lines.select(line.<Long>get("id")).where(
                        cb.equal(line.get("position"), root),
                        cb.isTrue(line.<Boolean>get("active")));
                where.add(cb.not(cb.exists(lines)));
            }
            if (f.noExternalOrder()) {
                Subquery<Long> lines = query.subquery(Long.class);
                Root<ExternalOrderLineEntity> line = lines.from(ExternalOrderLineEntity.class);
                lines.select(line.<Long>get("id")).where(
                        cb.equal(line.get("position"), root),
                        cb.notEqual(line.get("externalOrder").get("status"), ExternalOrderStatus.CANCELLED));
                where.add(cb.not(cb.exists(lines)));
            }
            BuyerMode buyer = f.buyer() == null ? BuyerMode.ALL : f.buyer();
            if (buyer == BuyerMode.MINE) {
                where.add(me == null ? cb.disjunction() : cb.equal(
                        root.join("purchaseRequest", JoinType.INNER).get("assignedBuyer").get("id"), me));
            } else if (buyer == BuyerMode.UNASSIGNED) {
                Join<PurchasePositionEntity, PurchaseRequestEntity> request =
                        root.join("purchaseRequest", JoinType.LEFT);
                where.add(cb.isNull(request.get("assignedBuyer")));
            }
            if (f.slice() != null && f.slice() != Slice.ALL) {
                where.add(PlannerSliceRules.slicePredicate(f.slice(), root, query, cb, today));
            }
            if (f.urgent()) {
                where.add(PlannerSliceRules.urgentPredicate(root, cb, today, urgentDays));
            }
            if (f.hideDelivered()) {
                Predicate hide = HideDeliveredFilter.positions().toPredicate(root, query, cb);
                if (hide != null) {
                    where.add(hide);
                }
            }
            if (f.q() != null && !f.q().isBlank()) {
                where.add(textPredicate(root, cb, f.q().trim()));
            }
            return cb.and(where.toArray(new Predicate[0]));
        };
    }

    private static Predicate textPredicate(Root<PurchasePositionEntity> root, CriteriaBuilder cb, String q) {
        String pattern = "%" + escapeLike(q.toLowerCase(Locale.ROOT)) + "%";
        List<Predicate> any = new ArrayList<>();
        for (String attribute : List.of("plannedMaterial", "actualMaterial")) {
            Join<PurchasePositionEntity, FinishingMaterialEntity> material = root.join(attribute, JoinType.LEFT);
            any.add(like(cb, material.get("model"), pattern));
            any.add(like(cb, material.get("sku"), pattern));
            Join<FinishingMaterialEntity, MaterialProducerEntity> producer = material.join("producer", JoinType.LEFT);
            any.add(like(cb, producer.get("nameRU"), pattern));
            any.add(like(cb, producer.get("namePL"), pattern));
            Join<FinishingMaterialEntity, MaterialSellerEntity> seller = material.join("seller", JoinType.LEFT);
            any.add(like(cb, seller.get("nameRU"), pattern));
            any.add(like(cb, seller.get("namePL"), pattern));
        }
        Join<PurchasePositionEntity, PurchaseRequestEntity> request = root.join("purchaseRequest", JoinType.LEFT);
        any.add(like(cb, request.get("referenceNumber"), pattern));
        any.add(like(cb, root.get("notes"), pattern));
        return cb.or(any.toArray(new Predicate[0]));
    }

    private static Predicate like(CriteriaBuilder cb, Expression<String> expression, String pattern) {
        return cb.like(cb.lower(expression), pattern, '\\');
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ------------------------------------------------------------------ sort

    private static Sort.Order sortOrder(Pageable pageable) {
        if (pageable == null || pageable.getSort().isUnsorted()) {
            return Sort.Order.asc("siteDeliveryDate");
        }
        Sort.Order first = pageable.getSort().iterator().next();
        if (!SORT_FIELDS.contains(first.getProperty())) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, SORT_INVALID);
        }
        return first;
    }

    /** Requested field with nulls last, then {@code id} ascending as a stable tie-break (R1.2). */
    private static List<Order> orders(Root<PurchasePositionEntity> root, CriteriaBuilder cb, Sort.Order order) {
        List<Order> orders = new ArrayList<>();
        Expression<?> expression = sortExpression(root, cb, order.getProperty());
        Expression<Integer> nullsLast = cb.<Integer>selectCase().when(cb.isNull(expression), 1).otherwise(0);
        orders.add(cb.asc(nullsLast));
        orders.add(order.isDescending() ? cb.desc(expression) : cb.asc(expression));
        orders.add(cb.asc(root.get("id")));
        return orders;
    }

    private static Expression<?> sortExpression(Root<PurchasePositionEntity> root, CriteriaBuilder cb,
                                                String property) {
        return switch (property) {
            case "siteDeliveryDate" -> cb.coalesce(root.<LocalDate>get("plannedDeliveryDate"),
                    root.<LocalDate>get("needByDate"));
            case "status" -> {
                CriteriaBuilder.SimpleCase<PositionStatus, Integer> rank =
                        cb.selectCase(root.<PositionStatus>get("status"));
                for (PositionStatus status : PositionStatus.values()) {
                    rank = rank.when(status, status.ordinal());
                }
                yield rank.otherwise(Integer.MAX_VALUE);
            }
            default -> root.get(property);
        };
    }

    private static void fetchAssociations(Root<PurchasePositionEntity> root) {
        root.fetch("project", JoinType.INNER);
        root.fetch("unit", JoinType.INNER);
        root.fetch("deliveryCategory", JoinType.LEFT);
        root.fetch("workCategory", JoinType.LEFT);
        root.fetch("purchaseVatRate", JoinType.LEFT);
        root.fetch("saleVatRate", JoinType.LEFT);
        Fetch<PurchasePositionEntity, PurchaseRequestEntity> request = root.fetch("purchaseRequest", JoinType.LEFT);
        request.fetch("assignedBuyer", JoinType.LEFT);
        for (String attribute : List.of("plannedMaterial", "actualMaterial")) {
            Fetch<PurchasePositionEntity, FinishingMaterialEntity> material = root.fetch(attribute, JoinType.LEFT);
            material.fetch("producer", JoinType.LEFT);
            material.fetch("seller", JoinType.LEFT);
            material.fetch("type", JoinType.LEFT);
        }
    }

    // ----------------------------------------------------------- row assembly

    private List<PlannerRowDto> assemble(List<PurchasePositionEntity> entities, Authentication auth) {
        if (entities.isEmpty()) {
            return List.of();
        }
        LocalDate today = sliceRules.today();
        int urgentDays = properties.urgentDays();
        boolean ru = isRussian();
        Audience audience = audienceResolver.resolve(auth);
        Callers callers = callers(auth);

        Map<Long, Integer> variantCounts = variantCounts(entities);
        Map<Long, WorkLink> links = workLinks(entities);
        boolean staff = audience == Audience.STAFF_FULL;
        Map<Long, ProformaRef> proformas = staff ? proformaRefs(entities) : Map.of();
        Map<Long, Integer> orderCounts = staff ? externalOrderCounts(entities) : Map.of();

        List<PlannerRowDto> rows = new ArrayList<>(entities.size());
        for (PurchasePositionEntity p : entities) {
            int variantCount = variantCounts.getOrDefault(p.getId(), 0);
            PositionSnapshot snapshot = new PositionSnapshot(p.getSourcingPath(), p.getStatus(), p.getNeedByDate(),
                    p.getOrderByDate(), p.getPlannedDeliveryDate(), variantCount);
            LocalDate site = PlannerSliceRules.siteDeliveryDate(snapshot);
            WorkLink link = links.get(p.getId());
            LocalDate workStart = link == null ? null : link.startDate();
            boolean risk = workStart != null && site != null && site.isAfter(workStart);

            FinishingMaterialEntity effective = effectiveMaterial(p);
            PurchaseRequestEntity request = p.getPurchaseRequest();
            rows.add(new PlannerRowDto(
                    p.getId(),
                    p.getProject().getId(),
                    p.getProject().getName(),
                    p.getKind(),
                    p.getSourcingPath(),
                    p.getStatus(),
                    PositionStage.statusCode(p.getStatus()),
                    material(p, effective, ru),
                    p.getQuantity(),
                    ru ? p.getUnit().getNameRU() : p.getUnit().getNamePL(),
                    p.getOrderedQuantity(),
                    p.getAcceptedQuantity(),
                    p.getBlockNo(),
                    p.getDeliveryCategory() == null ? null
                            : ru ? p.getDeliveryCategory().getNameRU() : p.getDeliveryCategory().getNamePL(),
                    p.getNeedByDate(),
                    p.getOrderByDate(),
                    p.getDecisionDeadline(),
                    p.getPlannedDeliveryDate(),
                    site,
                    request == null ? null : request.getId(),
                    request == null ? null : request.getReferenceNumber(),
                    request == null || request.getAssignedBuyer() == null ? null
                            : request.getAssignedBuyer().getName(),
                    p.getWorkCategory() == null ? null : p.getWorkCategory().getId(),
                    link == null ? null : link.categoryId(),
                    link == null ? null : link.name(),
                    link == null ? Source.NONE : link.source(),
                    link == null ? List.of() : link.candidates(),
                    workStart,
                    workStart,
                    PlannerSliceRules.isLate(snapshot, today),
                    PlannerSliceRules.isUrgent(snapshot, today, urgentDays),
                    risk,
                    variantCount > 0,
                    variantCount,
                    money(p, audience),
                    allowedActions(p, callers),
                    staff && proformas.containsKey(p.getId()) ? proformas.get(p.getId()).id() : null,
                    staff && proformas.containsKey(p.getId()) ? proformas.get(p.getId()).number() : null,
                    staff && proformas.containsKey(p.getId()) ? proformas.get(p.getId()).status() : null,
                    staff ? orderCounts.getOrDefault(p.getId(), 0) : null));
        }
        return rows;
    }

    /**
     * Ids of the given positions whose site-delivery date is after the start of their linked work
     * (R5.7), with the same batched resolution as the planner rows. Used by the board cards.
     */
    @Transactional(readOnly = true)
    public Set<Long> deliveryRiskIds(List<PurchasePositionEntity> entities) {
        Set<Long> risk = new HashSet<>();
        if (entities == null || entities.isEmpty()) {
            return risk;
        }
        Map<Long, WorkLink> links = workLinks(entities);
        for (PurchasePositionEntity p : entities) {
            WorkLink link = links.get(p.getId());
            LocalDate site = p.getPlannedDeliveryDate() != null ? p.getPlannedDeliveryDate() : p.getNeedByDate();
            if (link != null && link.startDate() != null && site != null && site.isAfter(link.startDate())) {
                risk.add(p.getId());
            }
        }
        return risk;
    }

    /** The proforma an active line puts a position on (R18.4); one aggregate query per page. */
    private record ProformaRef(Long id, String number, ProformaStatus status) {
    }

    private Map<Long, ProformaRef> proformaRefs(List<PurchasePositionEntity> entities) {
        Map<Long, ProformaRef> result = new HashMap<>();
        List<Object[]> rows = entityManager.createQuery(
                "select l.position.id, l.proforma.id, l.proforma.number, l.proforma.status "
                        + "from ClientProformaLineEntity l where l.active = true and l.position.id in :ids",
                Object[].class).setParameter("ids", entities.stream().map(PurchasePositionEntity::getId).toList())
                .getResultList();
        for (Object[] row : rows) {
            result.put((Long) row[0], new ProformaRef((Long) row[1], (String) row[2], (ProformaStatus) row[3]));
        }
        return result;
    }

    /** Number of non-cancelled external orders each position is on (R18.4); one aggregate query per page. */
    private Map<Long, Integer> externalOrderCounts(List<PurchasePositionEntity> entities) {
        Map<Long, Integer> result = new HashMap<>();
        List<Object[]> rows = entityManager.createQuery(
                "select l.position.id, count(l) from ExternalOrderLineEntity l "
                        + "where l.externalOrder.status <> :cancelled and l.position.id in :ids "
                        + "group by l.position.id", Object[].class)
                .setParameter("cancelled", ExternalOrderStatus.CANCELLED)
                .setParameter("ids", entities.stream().map(PurchasePositionEntity::getId).toList())
                .getResultList();
        for (Object[] row : rows) {
            result.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return result;
    }

    private Map<Long, Integer> variantCounts(List<PurchasePositionEntity> entities) {
        List<Long> ids = new ArrayList<>(entities.size());
        for (PurchasePositionEntity p : entities) {
            ids.add(p.getId());
        }
        Map<Long, Integer> counts = new HashMap<>();
        for (Object[] row : variantDao.countByPositionIds(ids)) {
            if (row[1] instanceof Number n) {
                counts.put((Long) row[0], n.intValue());
            }
        }
        return counts;
    }

    /** The resolved work link of a row (R5.6). */
    private record WorkLink(Long categoryId, String name, Source source,
                            List<WorkCategoryCandidate> candidates, LocalDate startDate) {
    }

    /**
     * Resolves the work link of every row of the page with a fixed number of batched lookups: the
     * scheduled categories per distinct project, tier 1 per distinct project, tier 2 once for the
     * unresolved material types, and the start dates of manual categories that are not scheduled.
     */
    private Map<Long, WorkLink> workLinks(List<PurchasePositionEntity> entities) {
        boolean ru = isRussian();
        Set<Long> projectIds = new LinkedHashSet<>();
        for (PurchasePositionEntity p : entities) {
            projectIds.add(p.getProject().getId());
        }
        Map<Long, Map<Long, Scheduled>> scheduledByProject = scheduledCategoryService.scheduled(projectIds);

        // tier 1 per project, only for projects with something scheduled and rows that need the lookup
        Map<Long, Map<Long, Set<Long>>> tier1ByProject = new HashMap<>();
        Set<Long> tier2Types = new HashSet<>();
        for (PurchasePositionEntity p : entities) {
            Long typeId = typeId(effectiveMaterial(p));
            Long projectId = p.getProject().getId();
            Map<Long, Scheduled> scheduled = scheduledByProject.getOrDefault(projectId, Map.of());
            if (p.getWorkCategory() != null || typeId == null || scheduled.isEmpty()) {
                continue;
            }
            Map<Long, Set<Long>> tier1 = tier1ByProject.computeIfAbsent(projectId,
                    consumptionLookup::estimateCategoriesByType);
            Set<Long> matched = new HashSet<>(tier1.getOrDefault(typeId, Set.of()));
            matched.retainAll(scheduled.keySet());
            if (matched.isEmpty()) {
                tier2Types.add(typeId);
            }
        }
        Map<Long, Set<Long>> tier2 = consumptionLookup.catalogCategoriesByType(tier2Types);

        Map<Long, Resolution> resolutions = new HashMap<>();
        Set<ProjectCategory> manualPairs = new HashSet<>();
        for (PurchasePositionEntity p : entities) {
            Long projectId = p.getProject().getId();
            Map<Long, Scheduled> scheduled = scheduledByProject.getOrDefault(projectId, Map.of());
            Long manualId = p.getWorkCategory() == null ? null : p.getWorkCategory().getId();
            Resolution resolution = categoryResolver.resolve(manualId, typeId(effectiveMaterial(p)),
                    tier1ByProject.getOrDefault(projectId, Map.of()), tier2, scheduled);
            resolutions.put(p.getId(), resolution);
            if (resolution.source() == Source.MANUAL && !scheduled.containsKey(manualId)) {
                manualPairs.add(new ProjectCategory(projectId, manualId));
            }
        }
        Map<ProjectCategory, LocalDate> manualStarts = workStartResolver.resolve(manualPairs);

        Map<Long, WorkLink> links = new HashMap<>();
        for (PurchasePositionEntity p : entities) {
            Resolution resolution = resolutions.get(p.getId());
            if (resolution.source() == Source.NONE) {
                continue;
            }
            Long projectId = p.getProject().getId();
            Map<Long, Scheduled> scheduled = scheduledByProject.getOrDefault(projectId, Map.of());
            Scheduled hit = scheduled.get(resolution.categoryId());
            String name;
            LocalDate start;
            if (hit != null) {
                name = hit.name();
                start = hit.startDate();
            } else {
                WorkCategoryEntity category = p.getWorkCategory();
                name = category == null ? null : ru ? category.getNameRU() : category.getNamePL();
                start = manualStarts.get(new ProjectCategory(projectId, resolution.categoryId()));
            }
            List<WorkCategoryCandidate> candidates = new ArrayList<>();
            for (Scheduled candidate : resolution.candidates()) {
                candidates.add(new WorkCategoryCandidate(candidate.categoryId(), candidate.name(),
                        candidate.startDate()));
            }
            links.put(p.getId(), new WorkLink(resolution.categoryId(), name, resolution.source(),
                    List.copyOf(candidates), start));
        }
        return links;
    }

    private static FinishingMaterialEntity effectiveMaterial(PurchasePositionEntity p) {
        return p.getActualMaterial() != null ? p.getActualMaterial() : p.getPlannedMaterial();
    }

    private static Long typeId(FinishingMaterialEntity material) {
        return material == null || material.getType() == null ? null : material.getType().getId();
    }

    private static Material material(PurchasePositionEntity p, FinishingMaterialEntity m, boolean ru) {
        MaterialProducerEntity producer = m.getProducer();
        MaterialSellerEntity seller = m.getSeller();
        return new Material(
                p.getPlannedMaterial().getId(),
                p.getActualMaterial() == null ? null : p.getActualMaterial().getId(),
                m.getModel(),
                m.getSku(),
                producer == null ? null : ru ? producer.getNameRU() : producer.getNamePL(),
                seller == null ? null : seller.getId(),
                seller == null ? null : ru ? seller.getNameRU() : seller.getNamePL(),
                m.getLink(),
                m.getPhoto());
    }

    // ------------------------------------------------------------------ money

    /** The Money block, or {@code null} for every audience except {@code STAFF_FULL} (R4.4, R9.2). */
    private static Money money(PurchasePositionEntity p, Audience audience) {
        if (audience != Audience.STAFF_FULL) {
            return null;
        }
        PositionMoney raw = new PositionMoney(p.getPlannedPurchasePrice(), p.getActualPurchasePrice(),
                idOf(p.getPurchaseVatRate()), p.getSalePrice(), idOf(p.getSaleVatRate()),
                p.getDeliveryCostShare());
        PositionMoney visible = PositionPriceVisibility.apply(audience, p.getSourcingPath(), p.getStatus(), raw);
        BigDecimal saleRate = rateOf(p.getSaleVatRate());
        PositionMoneyMath.Line sale = PositionMoneyMath.saleLine(visible.salePrice(), p.getQuantity(), saleRate);
        BigDecimal purchaseUnit = visible.actualPurchasePrice() != null
                ? visible.actualPurchasePrice() : visible.plannedPurchasePrice();
        BigDecimal costNet = PositionMoneyMath.purchaseNet(purchaseUnit, p.getQuantity());
        Margin margin = PositionMarginCalculator.margin(visible.salePrice(), visible.actualPurchasePrice(),
                visible.plannedPurchasePrice(), p.getQuantity(), visible.deliveryCostShare());
        boolean overCeiling = visible.actualPurchasePrice() != null && visible.salePrice() != null
                && visible.actualPurchasePrice().compareTo(visible.salePrice()) > 0;
        return new Money(visible.salePrice(), visible.plannedPurchasePrice(), visible.actualPurchasePrice(),
                visible.purchaseVatRateId(), visible.saleVatRateId(), visible.deliveryCostShare(),
                sale == null ? null : sale.net(), sale == null ? null : sale.gross(), costNet,
                margin.amount(), margin.pct(), overCeiling);
    }

    private static Long idOf(VatRateEntity rate) {
        return rate == null ? null : rate.getId();
    }

    private static BigDecimal rateOf(VatRateEntity rate) {
        return rate == null ? null : rate.getRate();
    }

    // ---------------------------------------------------------------- actions

    /** Caller facts resolved once per request. */
    private record Callers(boolean manager, boolean canUpdate, boolean canDelete) {
    }

    private Callers callers(Authentication auth) {
        boolean manager = hasRole(auth, "ADMIN") || hasRole(auth, "MANAGER");
        String role = roleCode(auth);
        boolean update = role != null && permissionEvaluator.isAllowed(role, RESOURCE, "UPDATE");
        boolean delete = role != null && permissionEvaluator.isAllowed(role, RESOURCE, "DELETE");
        return new Callers(manager, update, delete);
    }

    /** R1.7: derived from the transition policy, the stage rules and the caller's role and grants. */
    private List<Action> allowedActions(PurchasePositionEntity p, Callers caller) {
        EnumSet<Action> actions = EnumSet.noneOf(Action.class);
        PositionStatus status = p.getStatus();
        SourcingPath path = p.getSourcingPath();
        if (caller.canUpdate()) {
            if (status != PositionStatus.CANCELLED) {
                actions.add(Action.EDIT);
            }
            if (VariantStagePolicy.allowed(status, Actor.MANUAL)) {
                actions.add(Action.REPLACE_VARIANT);
            }
            if (caller.manager()) {
                if (transitionPolicy.decide(path, status, PositionStatus.WITH_BUYER, Actor.MANUAL)
                        == Decision.ALLOWED && path == SourcingPath.PACKAGE) {
                    actions.add(Action.SEND_TO_BUYER);
                }
                if (transitionPolicy.decide(path, status, PositionStatus.IN_DELIVERY, Actor.MANUAL)
                        == Decision.ALLOWED && path == SourcingPath.CLIENT_OWN) {
                    actions.add(Action.MARK_CLIENT_OWN_IN_DELIVERY);
                }
                if (transitionPolicy.decide(path, status, PositionStatus.CANCELLED, Actor.MANUAL)
                        == Decision.ALLOWED) {
                    actions.add(Action.CANCEL);
                }
                if (transitionPolicy.decide(path, status, PositionStatus.PLANNED, Actor.MANUAL)
                        == Decision.ALLOWED) {
                    actions.add(Action.REOPEN);
                }
            }
        }
        if (caller.manager() && caller.canDelete()
                && (status == PositionStatus.PLANNED || status == PositionStatus.CANCELLED)) {
            actions.add(Action.DELETE);
        }
        return List.copyOf(actions);
    }

    // ------------------------------------------------------------------ scope

    /** Project scope of the caller: ADMIN sees all, others their allowed projects (R1.4). */
    private record Scope(boolean all, Set<Long> projectIds) {
        boolean isEmpty() {
            return !all && projectIds.isEmpty();
        }
    }

    /** Project ids the caller may see, or {@code null} when unrestricted (ADMIN). */
    Set<Long> visibleProjectIds(Authentication auth) {
        Scope scope = scope(auth);
        return scope.all() ? null : scope.projectIds();
    }

    private Scope scope(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return new Scope(false, Set.of());
        }
        if (hasRole(auth, "ADMIN")) {
            return new Scope(true, Set.of());
        }
        Long userId = currentUserId(auth);
        if (userId == null) {
            return new Scope(false, Set.of());
        }
        Set<Long> allowed = projectAccess.allowedProjectIds(userId);
        return new Scope(false, allowed == null ? Set.of() : allowed);
    }

    private static Long currentUserId(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            return null;
        }
        try {
            return Long.parseLong(auth.getName().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean hasRole(Authentication auth, String code) {
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (("ROLE_" + code).equals(ga.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private static String roleCode(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String authority = ga.getAuthority();
            if (authority != null && authority.startsWith("ROLE_")) {
                return authority.substring("ROLE_".length());
            }
        }
        return null;
    }

    private static boolean isRussian() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
