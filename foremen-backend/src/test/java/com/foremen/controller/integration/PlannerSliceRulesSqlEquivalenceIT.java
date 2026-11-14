package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.foremen.config.procurement.ProcurementProperties;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchasePositionVariantEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.VariantReason;
import com.foremen.service.procurement.planner.PlannerFilter;
import com.foremen.service.procurement.planner.PlannerFilter.BuyerMode;
import com.foremen.service.procurement.planner.PlannerQueryService;
import com.foremen.service.procurement.planner.PlannerRowDto;
import com.foremen.service.procurement.planner.PlannerSliceRules;
import com.foremen.service.procurement.planner.PlannerSliceRules.PositionSnapshot;
import com.foremen.service.procurement.planner.PlannerSliceRules.Slice;

/**
 * FOR-11-01 task 6.1 - the SQL predicates of {@link PlannerSliceRules} select exactly the positions the
 * pure functions classify, over a seeded pseudo-random matrix of status x path x dates x variants
 * (boundaries today, today+urgentDays, and null dates included). Also checks the row flags and the
 * slice counts against the pure side.
 *
 * <p>Repeatability: unique project per run, rolled back after the test; fixed random seed.
 *
 * <p>Validates Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.6.
 */
class PlannerSliceRulesSqlEquivalenceIT extends ProcurementIntegrationTestBase {

    private static final int POSITIONS = 260;

    @Autowired
    private PlannerQueryService service;
    @Autowired
    private PlannerSliceRules rules;
    @Autowired
    private ProcurementProperties properties;

    private static final Authentication ADMIN = new UsernamePasswordAuthenticationToken("admin@foremen.com", "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    private static LocalDate offset(LocalDate today, Integer days) {
        return days == null ? null : today.plusDays(days);
    }

    @Test
    @DisplayName("every slice, urgent and the row flags match the pure rules on a boundary matrix")
    void sqlMatchesPureRules() {
        LocalDate today = rules.today();
        int urgentDays = properties.urgentDays();
        Integer[] dayOffsets = {null, null, -30, -1, 0, 1, urgentDays - 1, urgentDays, urgentDays + 1, 90};
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        FinishingMaterialEntity planned = material(project);
        FinishingMaterialEntity other = material(project);
        PositionStatus[] statuses = PositionStatus.values();
        SourcingPath[] paths = SourcingPath.values();
        Random random = new Random(11_042L);

        Map<Long, PositionSnapshot> expected = new HashMap<>();
        for (int i = 0; i < POSITIONS; i++) {
            PurchasePositionEntity p = position(project, planned, paths[random.nextInt(paths.length)],
                    statuses[i % statuses.length]);
            p.setNeedByDate(offset(today, dayOffsets[random.nextInt(dayOffsets.length)]));
            p.setOrderByDate(offset(today, dayOffsets[random.nextInt(dayOffsets.length)]));
            p.setPlannedDeliveryDate(offset(today, dayOffsets[random.nextInt(dayOffsets.length)]));
            int variants = random.nextInt(5) == 0 ? 1 + random.nextInt(2) : 0;
            for (int v = 0; v < variants; v++) {
                PurchasePositionVariantEntity variant = new PurchasePositionVariantEntity();
                variant.setPosition(p);
                variant.setMaterial(other);
                variant.setReason(VariantReason.ANALOG);
                em.persist(variant);
            }
            expected.put(p.getId(), new PositionSnapshot(p.getSourcingPath(), p.getStatus(), p.getNeedByDate(),
                    p.getOrderByDate(), p.getPlannedDeliveryDate(), variants));
        }
        em.flush();
        em.clear();

        for (Slice slice : Slice.values()) {
            for (boolean urgent : new boolean[] {false, true}) {
                Set<Long> pure = new HashSet<>();
                expected.forEach((id, s) -> {
                    boolean inSlice = PlannerSliceRules.inSlice(slice, s, today);
                    if (inSlice && (!urgent || PlannerSliceRules.isUrgent(s, today, urgentDays))) {
                        pure.add(id);
                    }
                });
                Set<Long> sql = idsOf(allRows(filter(project, slice, urgent)));
                assertThat(sql).as("slice=%s urgent=%s", slice, urgent).isEqualTo(pure);
            }
        }

        for (PlannerRowDto row : allRows(filter(project, Slice.ALL, false))) {
            PositionSnapshot s = expected.get(row.positionId());
            assertThat(row.late()).as("late of %s", row.positionId()).isEqualTo(PlannerSliceRules.isLate(s, today));
            assertThat(row.urgent()).as("urgent of %s", row.positionId())
                    .isEqualTo(PlannerSliceRules.isUrgent(s, today, urgentDays));
            assertThat(row.siteDeliveryDate()).isEqualTo(PlannerSliceRules.siteDeliveryDate(s));
        }

        Map<Slice, Long> counts = service.sliceCounts(filter(project, Slice.LATE, false), ADMIN);
        for (Slice slice : Slice.values()) {
            long pure = expected.values().stream().filter(s -> PlannerSliceRules.inSlice(slice, s, today)).count();
            assertThat(counts.get(slice)).as("count of %s", slice).isEqualTo(pure);
        }
        assertThat(counts.get(Slice.ALL)).isEqualTo(POSITIONS);
    }

    private PlannerFilter filter(ProjectEntity project, Slice slice, boolean urgent) {
        return new PlannerFilter(project.getId(), null, null, null, false, BuyerMode.ALL, slice, urgent, null, false);
    }

    private List<PlannerRowDto> allRows(PlannerFilter filter) {
        List<PlannerRowDto> rows = new ArrayList<>();
        int page = 0;
        Page<PlannerRowDto> result;
        do {
            result = service.plan(filter, PageRequest.of(page++, 100), ADMIN);
            rows.addAll(result.getContent());
        } while (result.hasNext());
        assertThat(rows).hasSize((int) result.getTotalElements());
        return rows;
    }

    private static Set<Long> idsOf(List<PlannerRowDto> rows) {
        Set<Long> ids = new HashSet<>();
        rows.forEach(r -> ids.add(r.positionId()));
        assertThat(ids).hasSize(rows.size());
        return ids;
    }
}
