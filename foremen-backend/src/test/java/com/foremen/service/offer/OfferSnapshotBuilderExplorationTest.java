package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;

/**
 * FOR-05-07b task 1: bug condition exploration for the snapshot builder (bugfix.md 1.2, 1.8).
 * These tests encode the EXPECTED behavior and are EXPECTED TO FAIL on unfixed code.
 *
 * <p>Compiles against the unfixed model: the package marker is checked through the serialized JSON.
 */
class OfferSnapshotBuilderExplorationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("1.2: a line applied to no room is absent; no empty group or room remains")
    void lineWithoutRoomCellsIsOmitted() {
        EstimateEntity estimate = new EstimateEntity();
        WorkCategoryEntity category = category(1L);
        estimate.getLines().add(line(estimate, work(10L, category), 1, List.of()));

        OfferSnapshot snapshot = OfferSnapshotBuilder.build(estimate, new BigDecimal("23"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        // Counterexample on unfixed code: groups = [1 group with 1 row, cells = []].
        assertThat(snapshot.groups().stream().flatMap(g -> g.rows().stream()).toList())
                .as("rows applied to no room must be omitted").isEmpty();
        assertThat(snapshot.groups()).as("no empty group").isEmpty();
        assertThat(snapshot.rooms()).as("no empty room column").isEmpty();
    }

    @Test
    @DisplayName("1.2: only the room-applied row survives next to a row without cells")
    void mixedLinesKeepOnlyAppliedRows() {
        EstimateEntity estimate = new EstimateEntity();
        WorkCategoryEntity category = category(1L);
        RoomEntity room = room(5L, "Kitchen");
        estimate.getLines().add(line(estimate, work(10L, category), 1,
                List.of(cell(room, "3.00"))));
        estimate.getLines().add(line(estimate, work(11L, category), 2, List.of()));

        OfferSnapshot snapshot = OfferSnapshotBuilder.build(estimate, new BigDecimal("23"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        List<OfferSnapshot.Row> rows = snapshot.groups().stream().flatMap(g -> g.rows().stream()).toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).cells()).isNotEmpty();
    }

    @Test
    @DisplayName("1.8: the snapshot carries the applied package code")
    void snapshotCarriesAppliedPackage() throws Exception {
        EstimateEntity estimate = new EstimateEntity();
        estimate.setAppliedPackageCode("PREMIUM");
        WorkCategoryEntity category = category(1L);
        estimate.getLines().add(line(estimate, work(10L, category), 1,
                List.of(cell(room(5L, "Kitchen"), "3.00"))));

        OfferSnapshot snapshot = OfferSnapshotBuilder.build(estimate, new BigDecimal("23"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        JsonNode json = MAPPER.valueToTree(snapshot);
        // Counterexample on unfixed code: no "appliedPackageCode" field in the snapshot.
        assertThat(json.has("appliedPackageCode")).as("snapshot has appliedPackageCode").isTrue();
        assertThat(json.path("appliedPackageCode").asText()).isEqualTo("PREMIUM");
    }

    // --- fixtures ---

    private static WorkCategoryEntity category(Long id) {
        WorkCategoryEntity c = new WorkCategoryEntity();
        c.setId(id);
        c.setNameRU("Cat");
        c.setNamePL("Cat");
        return c;
    }

    private static WorkItemEntity work(Long id, WorkCategoryEntity category) {
        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode("M2");
        unit.setNameRU("m2");
        unit.setNamePL("m2");
        WorkItemEntity w = new WorkItemEntity();
        w.setId(id);
        w.setWorkCategory(category);
        w.setUnit(unit);
        w.setNameRU("Work " + id);
        w.setNamePL("Work " + id);
        return w;
    }

    private static RoomEntity room(Long id, String label) {
        RoomEntity r = new RoomEntity();
        r.setId(id);
        r.setLabel(label);
        return r;
    }

    private static EstimateLineRoomQtyEntity cell(RoomEntity room, String qty) {
        EstimateLineRoomQtyEntity c = new EstimateLineRoomQtyEntity();
        c.setRoom(room);
        c.setQuantity(new BigDecimal(qty));
        return c;
    }

    private static EstimateLineEntity line(EstimateEntity estimate, WorkItemEntity work, int lineNo,
                                           List<EstimateLineRoomQtyEntity> cells) {
        EstimateLineEntity l = new EstimateLineEntity();
        l.setEstimate(estimate);
        l.setWorkItem(work);
        l.setLineNo(lineNo);
        l.setUnitPrice(new BigDecimal("10.00"));
        BigDecimal total = cells.stream().map(EstimateLineRoomQtyEntity::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        l.setQuantity(total);
        l.setRoomQtys(new ArrayList<>(cells));
        return l;
    }
}
