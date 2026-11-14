package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.foremen.dao.model.PositionApprovalOptionEntity;
import com.foremen.dao.model.PositionApprovalRoundEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.approval.ApprovalFilter;
import com.foremen.service.procurement.approval.ApprovalQueryService;
import com.foremen.service.procurement.approval.ApprovalRoundKind;
import com.foremen.service.procurement.approval.ApprovalRoundStatus;
import com.foremen.service.procurement.approval.ApprovalSummary;
import com.foremen.service.procurement.approval.ClientApprovalData;
import com.foremen.service.procurement.approval.ClientOptionSource;
import com.foremen.service.procurement.approval.ClientPositionSource;
import com.foremen.service.procurement.approval.ClientRoundSource;
import com.foremen.service.procurement.approval.ClientStatus;

/**
 * FOR-11-02 task 4.1 - {@link ApprovalQueryService}: ordering, in-query filters, summary counts and the
 * narrowed (sale-side only) projections.
 *
 * <p>Repeatability: unique per-run data inside a rolled-back transaction.
 *
 * <p>Validates Requirements 3.1, 5.1, 5.2, 5.4, 5.5, 14.2.
 */
class ApprovalQueryServiceIntegrationTest extends ProcurementIntegrationTestBase {

    private static final LocalDate TODAY = LocalDate.of(2030, 6, 15);

    @Autowired
    private ApprovalQueryService service;

    private PositionApprovalRoundEntity round(PurchasePositionEntity pos, int no, ApprovalRoundKind kind,
                                              ApprovalRoundStatus status, LocalDate deadline) {
        PositionApprovalRoundEntity r = new PositionApprovalRoundEntity();
        r.setPosition(pos);
        r.setRoundNo(no);
        r.setKind(kind);
        r.setStatus(status);
        r.setDecisionDeadline(deadline);
        r.setManagerNote("note " + no);
        em.persist(r);
        PositionApprovalOptionEntity o = new PositionApprovalOptionEntity();
        o.setRound(r);
        o.setMaterial(pos.getPlannedMaterial());
        o.setUnitSalePrice(new BigDecimal("100.00"));
        o.setInternalUnitPurchasePrice(new BigDecimal("59.99"));
        em.persist(o);
        em.flush();
        return r;
    }

    private PurchasePositionEntity pos(ProjectEntity p, SourcingPath path, PositionStatus st, Integer block) {
        PurchasePositionEntity pos = position(p, path, st);
        pos.setBlockNo(block);
        em.flush();
        return pos;
    }

    private record Seed(ProjectEntity project, PurchasePositionEntity awaitingB1, PurchasePositionEntity overdueB1,
                        PurchasePositionEntity acceptedB2, PurchasePositionEntity clientOwnNoBlock,
                        PurchasePositionEntity noRound) {
    }

    private Seed seed() {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity awaiting = pos(project, SourcingPath.AGREED, PositionStatus.PROPOSED, 1);
        round(awaiting, 1, ApprovalRoundKind.PROPOSAL, ApprovalRoundStatus.OPEN, TODAY.plusDays(3));
        awaiting.setDecisionDeadline(TODAY.plusDays(3));
        PurchasePositionEntity overdue = pos(project, SourcingPath.AGREED, PositionStatus.PROPOSED, 1);
        round(overdue, 1, ApprovalRoundKind.PROPOSAL, ApprovalRoundStatus.OPEN, TODAY.minusDays(1));
        overdue.setDecisionDeadline(TODAY.minusDays(1));
        PurchasePositionEntity accepted = pos(project, SourcingPath.AGREED, PositionStatus.ACCEPTED, 2);
        round(accepted, 1, ApprovalRoundKind.PROPOSAL, ApprovalRoundStatus.ACCEPTED, null);
        PurchasePositionEntity own = pos(project, SourcingPath.CLIENT_OWN, PositionStatus.PLANNED, null);
        round(own, 1, ApprovalRoundKind.CLIENT_OWN, ApprovalRoundStatus.DECLARED, null);
        PurchasePositionEntity none = pos(project, SourcingPath.AGREED, PositionStatus.PLANNED, 1);
        em.flush();
        return new Seed(project, awaiting, overdue, accepted, own, none);
    }

    private List<Long> ids(ClientApprovalData data) {
        return data.positions().stream().map(ClientPositionSource::positionId).toList();
    }

    @Test
    @DisplayName("lists only positions with a round, ordered by block (nulls last), open first, deadline")
    void orderingAndRoundRequirement() {
        Seed s = seed();
        ClientApprovalData data = service.loadClientData(s.project().getId(), ApprovalFilter.none(), TODAY);
        assertThat(ids(data)).containsExactly(s.overdueB1().getId(), s.awaitingB1().getId(),
                s.acceptedB2().getId(), s.clientOwnNoBlock().getId());
        assertThat(data.roundsByPosition().get(s.awaitingB1().getId())).hasSize(1);
        Long roundId = data.roundsByPosition().get(s.awaitingB1().getId()).get(0).roundId();
        assertThat(data.optionsByRound().get(roundId)).hasSize(1);
        assertThat(data.optionsByRound().get(roundId).get(0).unitSalePrice())
                .isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("filters status, onlyPending, blockNo, overdue and sourcingPath are applied in the query")
    void filters() {
        Seed s = seed();
        Long pid = s.project().getId();
        assertThat(ids(service.loadClientData(pid, new ApprovalFilter(ClientStatus.AWAITING_DECISION, false,
                null, false, null), TODAY))).containsExactlyInAnyOrder(s.awaitingB1().getId(), s.overdueB1().getId());
        assertThat(ids(service.loadClientData(pid, new ApprovalFilter(null, true, null, false, null), TODAY)))
                .containsExactlyInAnyOrder(s.awaitingB1().getId(), s.overdueB1().getId());
        assertThat(ids(service.loadClientData(pid, new ApprovalFilter(ClientStatus.ACCEPTED, false, null, false,
                null), TODAY))).containsExactly(s.acceptedB2().getId());
        assertThat(ids(service.loadClientData(pid, new ApprovalFilter(ClientStatus.CLIENT_OWN, false, null, false,
                null), TODAY))).containsExactly(s.clientOwnNoBlock().getId());
        assertThat(ids(service.loadClientData(pid, new ApprovalFilter(null, false, 2, false, null), TODAY)))
                .containsExactly(s.acceptedB2().getId());
        assertThat(ids(service.loadClientData(pid, new ApprovalFilter(null, false, null, true, null), TODAY)))
                .containsExactly(s.overdueB1().getId());
        assertThat(ids(service.loadClientData(pid, new ApprovalFilter(null, false, null, false,
                SourcingPath.CLIENT_OWN), TODAY))).containsExactly(s.clientOwnNoBlock().getId());
        assertThat(ids(service.loadClientData(pid, new ApprovalFilter(ClientStatus.RECEIVED, false, null, false,
                null), TODAY))).isEmpty();
    }

    @Test
    @DisplayName("summary counts awaiting, overdue, accepted and client-own")
    void summaryCounts() {
        Seed s = seed();
        ApprovalSummary summary = service.summary(s.project().getId(), TODAY);
        assertThat(summary).isEqualTo(new ApprovalSummary(2, 1, 1, 1));
    }

    @Test
    @DisplayName("single position: found with a round, empty without")
    void singlePosition() {
        Seed s = seed();
        assertThat(ids(service.loadClientPosition(s.acceptedB2().getId()))).containsExactly(s.acceptedB2().getId());
        assertThat(service.loadClientPosition(s.noRound().getId()).positions()).isEmpty();
    }

    @Test
    @DisplayName("client sources carry no purchase-side component")
    void projectionsAreSaleSideOnly() {
        List<String> deny = List.of("purchase", "internal", "delivery", "seller", "needby", "orderby", "request",
                "audit", "margin", "cost");
        for (Class<?> type : List.of(ClientPositionSource.class, ClientRoundSource.class,
                ClientOptionSource.class)) {
            Arrays.stream(type.getRecordComponents()).forEach(c -> assertThat(deny)
                    .noneMatch(token -> c.getName().toLowerCase().contains(token)));
        }
    }
}
