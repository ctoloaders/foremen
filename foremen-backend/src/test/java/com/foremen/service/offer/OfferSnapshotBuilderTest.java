package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPriceEntity;

/** FOR-05-07b task 3.2: snapshot v2 content (restore data, package marker, rows applied to a room only). */
class OfferSnapshotBuilderTest {

    private static final BigDecimal VAT = new BigDecimal("23");

    @Test
    @DisplayName("v2 row stores override, comment, work price id and line number; unitPrice stays effective")
    void rowCarriesRestoreData() {
        EstimateEntity estimate = new EstimateEntity();
        EstimateLineEntity line = line(estimate, 10L, 3, "5.00", room(1L, "A"));
        line.setUnitPriceOverride(new BigDecimal("8.00"));
        line.setComment("note");
        WorkPriceEntity price = new WorkPriceEntity();
        price.setId(77L);
        line.setWorkPrice(price);
        estimate.getLines().add(line);

        OfferSnapshot s = OfferSnapshotBuilder.build(estimate, VAT, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);

        OfferSnapshot.Row row = s.groups().get(0).rows().get(0);
        assertThat(s.version()).isEqualTo(2);
        assertThat(row.unitPriceOverride()).isEqualByComparingTo("8.00");
        assertThat(row.unitPrice()).isEqualByComparingTo("8.00");
        assertThat(row.baseUnitPrice()).isEqualByComparingTo("10.00");
        assertThat(row.comment()).isEqualTo("note");
        assertThat(row.workPriceId()).isEqualTo(77L);
        assertThat(row.lineNo()).isEqualTo(3);
    }

    @Test
    @DisplayName("rooms come only from surviving rows; zero-quantity cells do not count as applied")
    void roomsOnlyFromSurvivingRows() {
        EstimateEntity estimate = new EstimateEntity();
        estimate.getLines().add(line(estimate, 10L, 1, "2.00", room(1L, "A")));
        estimate.getLines().add(line(estimate, 11L, 2, "0.00", room(2L, "B")));

        OfferSnapshot s = OfferSnapshotBuilder.build(estimate, VAT, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);

        assertThat(s.groups().stream().mapToInt(g -> g.rows().size()).sum()).isEqualTo(1);
        assertThat(s.rooms()).extracting(OfferSnapshot.Room::id).containsExactly(1L);
    }

    @Test
    @DisplayName("package marker is stored when present and absent when null or blank")
    void packageMarker() {
        EstimateEntity estimate = new EstimateEntity();
        estimate.getLines().add(line(estimate, 10L, 1, "2.00", room(1L, "A")));

        OfferSnapshot with = OfferSnapshotBuilder.build(estimate, VAT, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, "PREMIUM", "Premium RU", "Premium PL");
        OfferSnapshot none = OfferSnapshotBuilder.build(estimate, VAT, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, null, "x", "y");

        assertThat(with.appliedPackageCode()).isEqualTo("PREMIUM");
        assertThat(with.appliedPackageNameRu()).isEqualTo("Premium RU");
        assertThat(with.appliedPackageNamePl()).isEqualTo("Premium PL");
        assertThat(none.appliedPackageCode()).isNull();
        assertThat(none.appliedPackageNameRu()).isNull();
    }

    private static RoomEntity room(Long id, String label) {
        RoomEntity r = new RoomEntity();
        r.setId(id);
        r.setLabel(label);
        return r;
    }

    private static EstimateLineEntity line(EstimateEntity estimate, Long workId, int lineNo, String qty,
                                           RoomEntity room) {
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setId(1L);
        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode("M2");
        WorkItemEntity work = new WorkItemEntity();
        work.setId(workId);
        work.setWorkCategory(category);
        work.setUnit(unit);
        EstimateLineRoomQtyEntity cell = new EstimateLineRoomQtyEntity();
        cell.setRoom(room);
        cell.setQuantity(new BigDecimal(qty));
        EstimateLineEntity l = new EstimateLineEntity();
        l.setEstimate(estimate);
        l.setWorkItem(work);
        l.setLineNo(lineNo);
        l.setUnitPrice(new BigDecimal("10.00"));
        l.setQuantity(new BigDecimal(qty));
        l.setRoomQtys(new ArrayList<>(List.of(cell)));
        return l;
    }
}
