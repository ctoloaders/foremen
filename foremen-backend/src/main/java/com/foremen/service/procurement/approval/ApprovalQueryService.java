package com.foremen.service.procurement.approval;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FOR-11-02 (design 4): read side. One query per project for positions that have at least one round, one
 * for their rounds and one for the options (materials, producers and units are joined in the constructor
 * projections), so there is no N+1. Every projection selects only sale-side columns. The filters
 * {@code status}, {@code onlyPending}, {@code blockNo}, {@code overdue} and {@code sourcingPath} are part
 * of the JPQL. Access scoping is the caller's responsibility ({@code ProcurementProjectAccess}).
 */
@Service
public class ApprovalQueryService {

    private static final String PKG = "com.foremen.service.procurement.approval.";
    private static final String OPEN_STATUS = PKG + "ApprovalRoundStatus.OPEN";
    private static final String SUGGESTION_KIND = PKG + "ApprovalRoundKind.CLIENT_SUGGESTION";
    private static final String CLIENT_OWN_PATH = "com.foremen.dao.model.SourcingPath.CLIENT_OWN";

    private static final String HAS_ROUND = "EXISTS (SELECT 1 FROM PositionApprovalRoundEntity x "
            + "WHERE x.position = p)";
    /** An open round the client has to decide on (client suggestions are the client's own, R4.3). */
    private static final String HAS_OPEN = "EXISTS (SELECT 1 FROM PositionApprovalRoundEntity o "
            + "WHERE o.position = p AND o.status = " + OPEN_STATUS + " AND o.kind <> " + SUGGESTION_KIND + ")";
    private static final String HAS_OVERDUE = "EXISTS (SELECT 1 FROM PositionApprovalRoundEntity od "
            + "WHERE od.position = p AND od.status = " + OPEN_STATUS + " AND od.kind <> " + SUGGESTION_KIND
            + " AND od.decisionDeadline < :today)";
    private static final String NOT_CLIENT_OWN = "p.sourcingPath <> " + CLIENT_OWN_PATH;

    private static final String AWAITING = "(" + NOT_CLIENT_OWN + " AND " + HAS_OPEN + ")";

    private final EntityManager entityManager;
    private final Clock clock;

    public ApprovalQueryService(EntityManager entityManager, Clock clock) {
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** Client view data of a project with "today" taken from the Warsaw calendar. */
    @Transactional(readOnly = true)
    public ClientApprovalData loadClientData(Long projectId, ApprovalFilter filter) {
        return loadClientData(projectId, filter, ApprovalDeadlinePolicy.today(clock));
    }

    /**
     * Positions with at least one round, in display order (R5.1): {@code blockNo} ascending with nulls last,
     * open rounds before closed, {@code decisionDeadline} ascending with nulls last, then id.
     */
    @Transactional(readOnly = true)
    public ClientApprovalData loadClientData(Long projectId, ApprovalFilter filter, LocalDate today) {
        return assemble(queryPositions("p.project.id = :scopeId", projectId, filter, today));
    }

    /** One position (R5.4); the result has no positions when it has no round. */
    @Transactional(readOnly = true)
    public ClientApprovalData loadClientPosition(Long positionId) {
        return assemble(queryPositions("p.id = :scopeId", positionId, ApprovalFilter.none(),
                ApprovalDeadlinePolicy.today(clock)));
    }

    /** Badge counts (R5.5) in one statement over the positions of the project that have a round. */
    @Transactional(readOnly = true)
    public ApprovalSummary summary(Long projectId) {
        return summary(projectId, ApprovalDeadlinePolicy.today(clock));
    }

    @Transactional(readOnly = true)
    public ApprovalSummary summary(Long projectId, LocalDate today) {
        String accepted = "(" + NOT_CLIENT_OWN + " AND NOT " + HAS_OPEN + " AND p.status = "
                + "com.foremen.dao.model.PositionStatus.ACCEPTED)";
        String jpql = "SELECT "
                + "COALESCE(SUM(CASE WHEN " + AWAITING + " THEN 1 ELSE 0 END), 0), "
                + "COALESCE(SUM(CASE WHEN " + NOT_CLIENT_OWN + " AND " + HAS_OVERDUE + " THEN 1 ELSE 0 END), 0), "
                + "COALESCE(SUM(CASE WHEN " + accepted + " THEN 1 ELSE 0 END), 0), "
                + "COALESCE(SUM(CASE WHEN p.sourcingPath = " + CLIENT_OWN_PATH + " THEN 1 ELSE 0 END), 0) "
                + "FROM PurchasePositionEntity p WHERE p.project.id = :projectId AND " + HAS_ROUND;
        Object[] row = entityManager.createQuery(jpql, Object[].class)
                .setParameter("projectId", projectId)
                .setParameter("today", today)
                .getSingleResult();
        return new ApprovalSummary(((Number) row[0]).longValue(), ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue(), ((Number) row[3]).longValue());
    }

    // ------------------------------------------------------------------ internals

    private List<ClientPositionSource> queryPositions(String scope, Long scopeId, ApprovalFilter filter,
                                                      LocalDate today) {
        ApprovalFilter f = filter == null ? ApprovalFilter.none() : filter;
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
        if (f.overdue()) {
            where.add(HAS_OVERDUE);
        }
        if (f.onlyPending()) {
            where.add(AWAITING);
        }
        if (f.status() != null) {
            where.add(statusPredicate(f.status(), params));
        }

        String jpql = "SELECT new " + PKG + "ClientPositionSource("
                + "p.id, p.blockNo, p.status, p.sourcingPath, p.quantity, u.nameRU, u.namePL, "
                + "p.salePrice, v.rate, p.decisionDeadline, "
                + item("id") + ", " + item("model") + ", " + item("sku") + ", "
                + "CASE WHEN am.id IS NOT NULL THEN amp.nameRU ELSE pmp.nameRU END, "
                + "CASE WHEN am.id IS NOT NULL THEN amp.namePL ELSE pmp.namePL END, "
                + item("link") + ", " + item("photo") + ") "
                + "FROM PurchasePositionEntity p JOIN p.unit u JOIN p.plannedMaterial pm "
                + "LEFT JOIN pm.producer pmp LEFT JOIN p.actualMaterial am LEFT JOIN am.producer amp "
                + "LEFT JOIN p.saleVatRate v "
                + "WHERE " + String.join(" AND ", where) + " "
                + "ORDER BY CASE WHEN p.blockNo IS NULL THEN 1 ELSE 0 END, p.blockNo, "
                + "CASE WHEN " + HAS_OPEN + " THEN 0 ELSE 1 END, "
                + "CASE WHEN p.decisionDeadline IS NULL THEN 1 ELSE 0 END, p.decisionDeadline, p.id";
        if (jpql.contains(":today")) {
            params.put("today", today);
        }
        TypedQuery<ClientPositionSource> query = entityManager.createQuery(jpql, ClientPositionSource.class);
        params.forEach(query::setParameter);
        return query.getResultList();
    }

    private static String item(String field) {
        return "CASE WHEN am.id IS NOT NULL THEN am." + field + " ELSE pm." + field + " END";
    }

    /** The position-level predicate of a {@link ClientStatus}, consistent with {@link ClientStatusMapper}. */
    private static String statusPredicate(ClientStatus status, Map<String, Object> params) {
        switch (status) {
            case CLIENT_OWN:
                return "p.sourcingPath = " + CLIENT_OWN_PATH;
            case AWAITING_DECISION:
                return AWAITING;
            default:
                Set<PositionStatus> statuses = EnumSet.noneOf(PositionStatus.class);
                for (PositionStatus ps : PositionStatus.values()) {
                    if (ClientStatusMapper.map(ps, SourcingPath.AGREED, false) == status) {
                        statuses.add(ps);
                    }
                }
                if (statuses.isEmpty()) {
                    return "1 = 0";
                }
                params.put("statuses", statuses);
                return "(" + NOT_CLIENT_OWN + " AND NOT " + HAS_OPEN + " AND p.status IN :statuses)";
        }
    }

    private ClientApprovalData assemble(List<ClientPositionSource> positions) {
        if (positions.isEmpty()) {
            return new ClientApprovalData(List.of(), Map.of(), Map.of());
        }
        List<Long> positionIds = positions.stream().map(ClientPositionSource::positionId).toList();
        List<ClientRoundSource> rounds = entityManager.createQuery(
                "SELECT new " + PKG + "ClientRoundSource(r.id, r.position.id, r.roundNo, r.kind, r.status, "
                        + "r.decisionDeadline, r.managerNote, r.suggestionLink, r.decisionComment, r.decidedAt, "
                        + "r.decidedSide, r.onBehalf, co.id) "
                        + "FROM PositionApprovalRoundEntity r LEFT JOIN r.chosenOption co "
                        + "WHERE r.position.id IN :ids ORDER BY r.position.id, r.roundNo",
                ClientRoundSource.class).setParameter("ids", positionIds).getResultList();
        Map<Long, List<ClientRoundSource>> byPosition = rounds.stream().collect(
                Collectors.groupingBy(ClientRoundSource::positionId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<ClientOptionSource>> optionsByRound = loadOptions(
                rounds.stream().map(ClientRoundSource::roundId).toList());
        return new ClientApprovalData(positions, byPosition, optionsByRound);
    }

    private Map<Long, List<ClientOptionSource>> loadOptions(Collection<Long> roundIds) {
        if (roundIds.isEmpty()) {
            return Map.of();
        }
        List<ClientOptionSource> options = entityManager.createQuery(
                "SELECT new " + PKG + "ClientOptionSource(o.id, o.round.id, o.sortOrder, o.unitSalePrice, "
                        + "m.id, m.model, m.sku, pr.nameRU, pr.namePL, m.link, m.photo) "
                        + "FROM PositionApprovalOptionEntity o JOIN o.material m LEFT JOIN m.producer pr "
                        + "WHERE o.round.id IN :ids ORDER BY o.round.id, o.sortOrder, o.id",
                ClientOptionSource.class).setParameter("ids", roundIds).getResultList();
        return options.stream().collect(
                Collectors.groupingBy(ClientOptionSource::roundId, LinkedHashMap::new, Collectors.toList()));
    }
}
