package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * FOR-05-07b task 3.5 property: lines with no positive room cell are skipped, groups and rooms derive only
 * from surviving rows, and the package marker is carried (blank code means none).
 *
 * <p><b>Validates: Requirements 2.1, 2.2, 2.3</b>
 */
class OfferSnapshotBuilderPropertyTest {

    private static final BigDecimal VAT = new BigDecimal("23");

    /** One line: category 1..3, per-room quantities in tenths (0 = no positive cell) for rooms 1..3. */
    record LineSpec(int category, List<Integer> tenths) {
    }

    @Provide
    Arbitrary<List<LineSpec>> lines() {
        Arbitrary<LineSpec> spec = Arbitraries.integers().between(1, 3).flatMap(c ->
                Arbitraries.integers().between(0, 5).list().ofSize(3).map(t -> new LineSpec(c, t)));
        return spec.list().ofMaxSize(8);
    }

    @Provide
    Arbitrary<String> packageCodes() {
        return Arbitraries.of("PREMIUM", "STANDARD", "", "  ", null);
    }

    @Property(tries = 200)
    void survivingRowsDriveGroupsAndRooms(@ForAll("lines") List<LineSpec> specs,
                                          @ForAll("packageCodes") String packageCode) {
        EstimateEntity estimate = new EstimateEntity();
        Set<Long> expectedWorks = new HashSet<>();
        Set<Long> expectedCategories = new HashSet<>();
        Set<Long> expectedRooms = new HashSet<>();
        long workId = 100;
        for (int i = 0; i < specs.size(); i++) {
            LineSpec spec = specs.get(i);
            workId++;
            estimate.getLines().add(line(estimate, workId, i + 1, spec));
            boolean applied = false;
            for (int r = 0; r < spec.tenths().size(); r++) {
                if (spec.tenths().get(r) > 0) {
                    applied = true;
                    expectedRooms.add((long) r + 1);
                }
            }
            if (applied) {
                expectedWorks.add(workId);
                expectedCategories.add((long) spec.category());
            }
        }

        OfferSnapshot s = OfferSnapshotBuilder.build(estimate, VAT, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, packageCode, "RU", "PL");

        Set<Long> actualWorks = new HashSet<>();
        s.groups().forEach(g -> g.rows().forEach(r -> {
            actualWorks.add(r.workItemId());
            assertThat(r.cells()).isNotEmpty().allMatch(c -> c.quantity().signum() > 0);
        }));
        assertThat(actualWorks).isEqualTo(expectedWorks);
        assertThat(s.groups()).allMatch(g -> !g.rows().isEmpty());
        assertThat(new HashSet<>(s.groups().stream().map(OfferSnapshot.Group::categoryId).toList()))
                .isEqualTo(expectedCategories);
        assertThat(new HashSet<>(s.rooms().stream().map(OfferSnapshot.Room::id).toList())).isEqualTo(expectedRooms);

        boolean hasPackage = packageCode != null && !packageCode.isBlank();
        assertThat(s.appliedPackageCode()).isEqualTo(hasPackage ? packageCode : null);
        assertThat(s.appliedPackageNameRu()).isEqualTo(hasPackage ? "RU" : null);
        assertThat(s.appliedPackageNamePl()).isEqualTo(hasPackage ? "PL" : null);
    }

    private static EstimateLineEntity line(EstimateEntity estimate, long workId, int lineNo, LineSpec spec) {
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setId((long) spec.category());
        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode("M2");
        WorkItemEntity work = new WorkItemEntity();
        work.setId(workId);
        work.setWorkCategory(category);
        work.setUnit(unit);
        List<EstimateLineRoomQtyEntity> cells = new ArrayList<>();
        for (int r = 0; r < spec.tenths().size(); r++) {
            RoomEntity room = new RoomEntity();
            room.setId((long) r + 1);
            room.setLabel("R" + (r + 1));
            EstimateLineRoomQtyEntity cell = new EstimateLineRoomQtyEntity();
            cell.setRoom(room);
            cell.setQuantity(BigDecimal.valueOf(spec.tenths().get(r), 1));
            cells.add(cell);
        }
        EstimateLineEntity l = new EstimateLineEntity();
        l.setEstimate(estimate);
        l.setWorkItem(work);
        l.setLineNo(lineNo);
        l.setUnitPrice(new BigDecimal("10.00"));
        l.setQuantity(BigDecimal.ONE);
        l.setRoomQtys(cells);
        return l;
    }
}
