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
import com.foremen.service.estimate.CellAiMeta;

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
        assertThat(s.version()).isEqualTo(3);
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

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static CellAiMeta sampleAi() {
        return new CellAiMeta("JOB", null, "ASSUMED", new BigDecimal("0.72"), new CellAiMeta.Source("plan.pdf", 3),
                "Walls are tiled.", new CellAiMeta.Volume("MODEL_OVERRIDE", "fallback", new BigDecimal("12.40"),
                        new BigDecimal("14.00"), new CellAiMeta.Correction(new BigDecimal("1.6"), new BigDecimal("12.9")),
                        "Niche.", null),
                null, null);
    }

    @Test
    @DisplayName("task 3.11: v3 cell copies aiMeta and the override flag; manual cells stay v2-shaped")
    void cellCopiesAiMetaAndOverride() {
        EstimateEntity estimate = new EstimateEntity();
        EstimateLineEntity ai = line(estimate, 10L, 1, "14.00", room(1L, "A"));
        ai.getRoomQtys().get(0).setVolumeOverridden(true);
        ai.getRoomQtys().get(0).setAiMeta(sampleAi());
        estimate.getLines().add(ai);
        estimate.getLines().add(line(estimate, 11L, 2, "2.00", room(1L, "A")));

        OfferSnapshot s = OfferSnapshotBuilder.build(estimate, VAT, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        List<OfferSnapshot.Row> rows = s.groups().get(0).rows();

        OfferSnapshot.Cell aiCell = rows.get(0).cells().get(0);
        assertThat(aiCell.ai()).isEqualTo(sampleAi());
        assertThat(aiCell.volumeOverridden()).isTrue();
        assertThat(aiCell.formulaKey()).isEqualTo("fallback");
        assertThat(aiCell.fallbackUsed()).isTrue();
        // task 3.12: a manual cell has no override or AI fields; "no norms" is an explicit empty material list
        assertThat(rows.get(1).cells().get(0)).isEqualTo(
                new OfferSnapshot.Cell(1L, new BigDecimal("2.00"), null, null, null, null, List.of()));
    }

    @Test
    @DisplayName("task 3.11: JSON round-trip keeps ai; a version 2 cell JSON still reads with null new fields")
    void jsonRoundTripAndVersion2Read() throws Exception {
        OfferSnapshot.Cell cell = new OfferSnapshot.Cell(1L, new BigDecimal("14.00"), true, "fallback", true, sampleAi());
        String json = JSON.writeValueAsString(cell);
        assertThat(JSON.readValue(json, OfferSnapshot.Cell.class)).isEqualTo(cell);

        String v2 = "{\"roomId\":5,\"quantity\":2.5}";
        OfferSnapshot.Cell old = JSON.readValue(v2, OfferSnapshot.Cell.class);
        assertThat(old).isEqualTo(new OfferSnapshot.Cell(5L, new BigDecimal("2.5")));
        assertThat(old.ai()).isNull();
        assertThat(old.volumeOverridden()).isNull();
        // A manual cell serializes exactly as a version 2 cell (no null noise).
        assertThat(JSON.writeValueAsString(old)).isEqualTo(v2);
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
