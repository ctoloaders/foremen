package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.PositionApprovalOptionEntity;
import com.foremen.dao.model.PositionApprovalRoundEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.approval.ApprovalRoundKind;
import com.foremen.service.procurement.approval.ApprovalRoundStatus;
import com.foremen.service.procurement.approval.StaffApprovalData;
import com.foremen.service.procurement.approval.StaffApprovalFilter;
import com.foremen.service.procurement.approval.StaffApprovalQueryService;
import com.foremen.service.procurement.approval.StaffPositionSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * FOR-11-02 task 4.3 - {@link StaffApprovalQueryService}: ordering, filters and purchase-side projections.
 *
 * <p>Repeatability: unique per-run data inside a rolled-back transaction.
 *
 * <p>Validates Requirements 4.5, 14.2.
 */
class StaffApprovalQueryServiceIntegrationTest extends ProcurementIntegrationTestBase {

    private static final LocalDate TODAY = LocalDate.of(2030, 6, 15);

    @Autowired
    private StaffApprovalQueryService service;

    private void round(PurchasePositionEntity pos, ApprovalRoundStatus status, LocalDate deadline) {
        PositionApprovalRoundEntity r = new PositionApprovalRoundEntity();
        r.setPosition(pos);
        r.setRoundNo(1);
        r.setKind(ApprovalRoundKind.PROPOSAL);
        r.setStatus(status);
        r.setDecisionDeadline(deadline);
        em.persist(r);
        PositionApprovalOptionEntity o = new PositionApprovalOptionEntity();
        o.setRound(r);
        o.setMaterial(pos.getPlannedMaterial());
        o.setUnitSalePrice(new BigDecimal("100.00"));
        o.setInternalUnitPurchasePrice(new BigDecimal("59.99"));
        em.persist(o);
        em.flush();
    }

    private PurchasePositionEntity pos(ProjectEntity p, PositionStatus st, Integer block) {
        PurchasePositionEntity pos = position(p, SourcingPath.AGREED, st);
        pos.setBlockNo(block);
        pos.setPlannedPurchasePrice(new BigDecimal("61.37"));
        em.flush();
        return pos;
    }

    @Test
    @DisplayName("staff data exposes purchase-side values, orders open rounds first and applies filters")
    void staffProjectionAndFilters() {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        PurchasePositionEntity overdue = pos(project, PositionStatus.PROPOSED, 1);
        round(overdue, ApprovalRoundStatus.OPEN, TODAY.minusDays(1));
        PurchasePositionEntity accepted = pos(project, PositionStatus.ACCEPTED, 2);
        round(accepted, ApprovalRoundStatus.ACCEPTED, null);
        PurchasePositionEntity unsent = pos(project, PositionStatus.PLANNED, 1); // never sent (R19.1)
        PurchasePositionEntity clientOwnUnsent = position(project, SourcingPath.CLIENT_OWN, PositionStatus.PLANNED);
        Long pid = project.getId();

        StaffApprovalData all = service.loadStaffData(pid, StaffApprovalFilter.none(), TODAY);
        assertThat(all.positions()).extracting(StaffPositionSource::positionId)
                .containsExactly(overdue.getId(), unsent.getId(), accepted.getId())
                .doesNotContain(clientOwnUnsent.getId());
        assertThat(all.roundsByPosition()).doesNotContainKey(unsent.getId());
        StaffPositionSource first = all.positions().get(0);
        assertThat(first.plannedPurchasePrice()).isEqualByComparingTo("61.37");
        Long roundId = all.roundsByPosition().get(first.positionId()).get(0).roundId();
        assertThat(all.optionsByRound().get(roundId).get(0).internalUnitPurchasePrice())
                .isEqualByComparingTo("59.99");

        assertThat(ids(service.loadStaffData(pid, new StaffApprovalFilter(null, true, null, null), TODAY)))
                .containsExactly(overdue.getId());
        assertThat(ids(service.loadStaffData(pid, new StaffApprovalFilter(Set.of(PositionStatus.ACCEPTED), false,
                null, null), TODAY))).containsExactly(accepted.getId());
        assertThat(ids(service.loadStaffData(pid, new StaffApprovalFilter(null, false, 2, null), TODAY)))
                .containsExactly(accepted.getId());
        assertThat(service.loadStaffPosition(overdue.getId()).positions()).hasSize(1);
        assertThat(service.loadStaffPosition(unsent.getId()).positions()).hasSize(1);
        assertThat(service.loadStaffPosition(clientOwnUnsent.getId()).positions()).isEmpty();
    }

    private static List<Long> ids(StaffApprovalData d) {
        return d.positions().stream().map(StaffPositionSource::positionId).toList();
    }
}
