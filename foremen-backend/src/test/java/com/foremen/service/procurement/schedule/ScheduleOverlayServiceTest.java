package com.foremen.service.procurement.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.ShipmentStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.schedule.ScheduleOverlayService.CategoryInfo;
import com.foremen.service.procurement.schedule.ScheduleOverlayService.OverlayCategory;
import com.foremen.service.procurement.schedule.ScheduleOverlayService.ScheduleOverlayView;

/** FOR-11-04 (R13): assembly of the materials overlay. */
class ScheduleOverlayServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final LocalDate ANCHOR = LocalDate.of(2026, 3, 1);

    private static PurchasePositionEntity position(long id, PositionStatus status, LocalDate orderBy,
                                                   LocalDate arrival) {
        PurchasePositionEntity p = new PurchasePositionEntity();
        p.setId(id);
        p.setStatus(status);
        p.setSourcingPath(SourcingPath.AGREED);
        p.setOrderByDate(orderBy);
        p.setPlannedDeliveryDate(arrival);
        FinishingMaterialEntity m = new FinishingMaterialEntity();
        m.setModel("Tile " + id);
        p.setPlannedMaterial(m);
        return p;
    }

    @Test
    void groupsPositionsByCategoryAndComputesFlags() {
        LocalDate worksStart = LocalDate.of(2026, 3, 12);
        PurchasePositionEntity late = position(1, PositionStatus.IN_DELIVERY, null, LocalDate.of(2026, 3, 15));
        PurchasePositionEntity missed = position(2, PositionStatus.PLANNED, LocalDate.of(2026, 3, 9), null);
        PurchasePositionEntity unlinked = position(3, PositionStatus.PLANNED, null, null);
        PurchasePositionEntity cancelled = position(4, PositionStatus.CANCELLED, null, null);

        ScheduleOverlayView view = ScheduleOverlayService.assemble(7L, ANCHOR,
                new LinkedHashSet<>(List.of(10L, 11L)),
                Map.of(10L, new CategoryInfo("TILING", "Tiling"), 11L, new CategoryInfo("PAINT", "Paint")),
                Map.of(), List.of(late, missed, unlinked, cancelled),
                Map.of(1L, 10L, 2L, 10L, 4L, 10L), Map.of(10L, worksStart),
                Map.of(1L, ShipmentStatus.IN_TRANSIT), TODAY);

        assertThat(view.projectId()).isEqualTo(7L);
        assertThat(view.anchorDate()).isEqualTo(ANCHOR);
        assertThat(view.categories()).extracting(OverlayCategory::code).containsExactly("TILING", "PAINT");
        OverlayCategory tiling = view.categories().get(0);
        assertThat(tiling.worksStartDate()).isEqualTo(worksStart);
        assertThat(tiling.positions()).hasSize(2);
        var first = tiling.positions().get(0);
        assertThat(first.downtimeRisk()).isTrue();
        assertThat(first.noArrivalDate()).isFalse();
        assertThat(first.shipmentStatus()).isEqualTo(ShipmentStatus.IN_TRANSIT);
        assertThat(first.label()).isEqualTo("Tile 1");
        var second = tiling.positions().get(1);
        assertThat(second.orderByMissed()).isTrue();
        assertThat(second.noArrivalDate()).isTrue();
        assertThat(second.downtimeRisk()).isFalse();
        assertThat(second.shipmentStatus()).isNull();
        assertThat(view.categories().get(1).positions()).isEmpty();
        assertThat(view.categories().get(1).worksStartDate()).isNull();
        assertThat(view.unlinked()).extracting(p -> p.positionId()).containsExactly(3L);
    }

    @Test
    void noWorksStartMeansNoDowntimeRisk() {
        PurchasePositionEntity p = position(1, PositionStatus.ORDERED, null, LocalDate.of(2026, 4, 1));
        ScheduleOverlayView view = ScheduleOverlayService.assemble(7L, null,
                new LinkedHashSet<>(Set.of(10L)), Map.of(10L, new CategoryInfo("C", "C")), Map.of(),
                List.of(p), Map.of(1L, 10L), Map.of(), Map.of(), TODAY);
        assertThat(view.categories().get(0).worksStartDate()).isNull();
        assertThat(view.categories().get(0).positions().get(0).downtimeRisk()).isFalse();
    }

    @Test
    void emptyProjectGivesEmptyCategories() {
        ScheduleOverlayView view = ScheduleOverlayService.assemble(7L, ANCHOR, Set.of(), Map.of(), Map.of(),
                List.of(), Map.of(), Map.of(), Map.of(), TODAY);
        assertThat(view.categories()).isEmpty();
        assertThat(view.unlinked()).isEmpty();
    }

    @Test
    void manualUnscheduledCategoriesFollowScheduledOnesByName() {
        PurchasePositionEntity p = position(1, PositionStatus.PLANNED, null, null);
        Map<Long, CategoryInfo> extra = new LinkedHashMap<>();
        extra.put(30L, new CategoryInfo("Z", "Zeta"));
        extra.put(20L, new CategoryInfo("A", "Alpha"));
        ScheduleOverlayView view = ScheduleOverlayService.assemble(7L, ANCHOR,
                new LinkedHashSet<>(Set.of(10L)), Map.of(10L, new CategoryInfo("S", "Sched")), extra,
                List.of(p), Map.of(1L, 30L), Map.of(), Map.of(), TODAY);
        assertThat(view.categories()).extracting(OverlayCategory::workCategoryId).containsExactly(10L, 20L, 30L);
        assertThat(view.categories().get(2).positions()).hasSize(1);
    }
}
