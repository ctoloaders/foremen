package com.foremen.service.procurement.approval;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FOR-11-02 (design 4): staff side of the read model. Same shape as {@link ApprovalQueryService} (one query
 * for positions, one for rounds, one for options, no N+1) but the projections include purchase-side
 * values, so the result must only be mapped by {@code StaffViewAssembler} after the audience check.
 * Access scoping is the caller's responsibility ({@code ProcurementProjectAccess}).
 */
@Service
public class StaffApprovalQueryService {

    private static final String PKG = "com.foremen.service.procurement.approval.";
    private static final String OPEN_STATUS = PKG + "ApprovalRoundStatus.OPEN";
    private static final String SUGGESTION_KIND = PKG + "ApprovalRoundKind.CLIENT_SUGGESTION";

    private static final String HAS_ROUND = "EXISTS (SELECT 1 FROM PositionApprovalRoundEntity x "
            + "WHERE x.position = p)";
    private static final String HAS_OPEN = "EXISTS (SELECT 1 FROM PositionApprovalRoundEntity o "
            + "WHERE o.position = p AND o.status = " + OPEN_STATUS + " AND o.kind <> " + SUGGESTION_KIND + ")";
    private static final String HAS_OVERDUE = "EXISTS (SELECT 1 FROM PositionApprovalRoundEntity od "
            + "WHERE od.position = p AND od.status = " + OPEN_STATUS + " AND od.kind <> " + SUGGESTION_KIND
            + " AND od.decisionDeadline < :today)";

    private final EntityManager entityManager;
    private final Clock clock;

    public StaffApprovalQueryService(EntityManager entityManager, Clock clock) {
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StaffApprovalData loadStaffData(Long projectId, StaffApprovalFilter filter) {
        return loadStaffData(projectId, filter, ApprovalDeadlinePolicy.today(clock));
    }

    /** Positions with at least one round in display order (blockNo nulls last, open first, deadline, id). */
    @Transactional(readOnly = true)
    public StaffApprovalData loadStaffData(Long projectId, StaffApprovalFilter filter, LocalDate today) {
        return assemble(queryPositions("p.project.id = :scopeId", projectId, filter, today));
    }

    /** One position with its full round history; no positions when it has no round. */
    @Transactional(readOnly = true)
    public StaffApprovalData loadStaffPosition(Long positionId) {
        return assemble(queryPositions("p.id = :scopeId", positionId, StaffApprovalFilter.none(),
                ApprovalDeadlinePolicy.today(clock)));
    }

    private List<StaffPositionSource> queryPositions(String scope, Long scopeId, StaffApprovalFilter filter,
                                                     LocalDate today) {
        StaffApprovalFilter f = filter == null ? StaffApprovalFilter.none() : filter;
        List<String> where = new ArrayList<>();
        where.add(scope);
        where.add(HAS_ROUND);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("scopeId", scopeId);
        if (f.blockNo() != null) {
            where.add("p.blockNo = :blockNo");
            params.put("blockNo", f.blockNo());
        }
        if (f.sourcingPath() != null) {
            where.add("p.sourcingPath = :path");
            params.put("path", f.sourcingPath());
        }
        if (f.statuses() != null && !f.statuses().isEmpty()) {
            where.add("p.status IN :statuses");
            params.put("statuses", f.statuses());
        }
        if (f.overdue()) {
            where.add(HAS_OVERDUE);
            params.put("today", today);
        }

        String jpql = "SELECT new " + PKG + "StaffPositionSource("
                + "p.id, p.blockNo, p.status, p.sourcingPath, p.quantity, u.nameRU, u.namePL, "
                + "p.salePrice, p.plannedPurchasePrice, p.actualPurchasePrice, p.deliveryCostShare, "
                + "p.needByDate, p.orderByDate, p.plannedDeliveryDate, p.decisionDeadline, "
                + "rq.id, rq.referenceNumber, "
                + item("id") + ", " + item("model") + ", " + item("sku") + ", "
                + "CASE WHEN am.id IS NOT NULL THEN amp.nameRU ELSE pmp.nameRU END, "
                + "CASE WHEN am.id IS NOT NULL THEN amp.namePL ELSE pmp.namePL END, "
                + item("link") + ", "
                + "CASE WHEN am.id IS NOT NULL THEN ams.nameRU ELSE pms.nameRU END, "
                + "CASE WHEN am.id IS NOT NULL THEN ams.namePL ELSE pms.namePL END) "
                + "FROM PurchasePositionEntity p JOIN p.unit u JOIN p.plannedMaterial pm "
                + "LEFT JOIN pm.producer pmp LEFT JOIN pm.seller pms "
                + "LEFT JOIN p.actualMaterial am LEFT JOIN am.producer amp LEFT JOIN am.seller ams "
                + "LEFT JOIN p.purchaseRequest rq "
                + "WHERE " + String.join(" AND ", where) + " "
                + "ORDER BY CASE WHEN p.blockNo IS NULL THEN 1 ELSE 0 END, p.blockNo, "
                + "CASE WHEN " + HAS_OPEN + " THEN 0 ELSE 1 END, "
                + "CASE WHEN p.decisionDeadline IS NULL THEN 1 ELSE 0 END, p.decisionDeadline, p.id";
        TypedQuery<StaffPositionSource> query = entityManager.createQuery(jpql, StaffPositionSource.class);
        params.forEach(query::setParameter);
        return query.getResultList();
    }

    private static String item(String field) {
        return "CASE WHEN am.id IS NOT NULL THEN am." + field + " ELSE pm." + field + " END";
    }

    private StaffApprovalData assemble(List<StaffPositionSource> positions) {
        if (positions.isEmpty()) {
            return new StaffApprovalData(List.of(), Map.of(), Map.of());
        }
        List<Long> positionIds = positions.stream().map(StaffPositionSource::positionId).toList();
        List<StaffRoundSource> rounds = entityManager.createQuery(
                "SELECT new " + PKG + "StaffRoundSource(r.id, r.position.id, r.roundNo, r.kind, r.status, "
                        + "r.decisionDeadline, r.managerNote, r.suggestionLink, r.decisionComment, r.decidedAt, "
                        + "db.id, r.decidedSide, r.onBehalf, ss.id, co.id) "
                        + "FROM PositionApprovalRoundEntity r LEFT JOIN r.decidedBy db "
                        + "LEFT JOIN r.sourceSuggestion ss LEFT JOIN r.chosenOption co "
                        + "WHERE r.position.id IN :ids ORDER BY r.position.id, r.roundNo",
                StaffRoundSource.class).setParameter("ids", positionIds).getResultList();
        Map<Long, List<StaffRoundSource>> byPosition = rounds.stream().collect(
                Collectors.groupingBy(StaffRoundSource::positionId, LinkedHashMap::new, Collectors.toList()));
        return new StaffApprovalData(positions, byPosition,
                loadOptions(rounds.stream().map(StaffRoundSource::roundId).toList()));
    }

    private Map<Long, List<StaffOptionSource>> loadOptions(Collection<Long> roundIds) {
        if (roundIds.isEmpty()) {
            return Map.of();
        }
        List<StaffOptionSource> options = entityManager.createQuery(
                "SELECT new " + PKG + "StaffOptionSource(o.id, o.round.id, o.sortOrder, o.unitSalePrice, "
                        + "o.internalUnitPurchasePrice, m.id, m.model, m.sku, pr.nameRU, pr.namePL, m.link, "
                        + "s.nameRU, s.namePL) "
                        + "FROM PositionApprovalOptionEntity o JOIN o.material m LEFT JOIN m.producer pr "
                        + "LEFT JOIN m.seller s "
                        + "WHERE o.round.id IN :ids ORDER BY o.round.id, o.sortOrder, o.id",
                StaffOptionSource.class).setParameter("ids", roundIds).getResultList();
        return options.stream().collect(
                Collectors.groupingBy(StaffOptionSource::roundId, LinkedHashMap::new, Collectors.toList()));
    }
}
