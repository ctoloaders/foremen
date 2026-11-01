package com.foremen.service.estimate.recalc;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.foremen.dao.model.ConsumptionBasis;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkItemEntity;

/**
 * FOR-05-05 additional task 2 — the before/after estimate diff that drives the room-save approval:
 * unchanged cells are not reported; a Volume change is CHANGED with old→new Volume and labour;
 * a removed / added cell is REMOVED / ADDED; material quantity changes are counted; an overridden
 * material keeps its manual quantity and is therefore not counted.
 */
class EstimateSnapshotTest {

    private final RoomTypeEntity bathroom = roomType(7L, "Ванная", "Łazienka");
    private final RoomEntity room1 = room(1L, null, bathroom);
    private final RoomEntity room2 = room(2L, "Salon", bathroom);
    private final WorkItemEntity tiling = work(100L, "Укладка плитки", "Układanie płytek");

    @Test
    void identicalSnapshotsProduceNoChanges() {
        EstimateEntity estimate = estimate();
        EstimateLineEntity line = line(estimate, tiling, "50.00");
        cell(line, room1, "10");

        EstimateImpact impact = EstimateSnapshot.diff(EstimateSnapshot.of(estimate), EstimateSnapshot.of(estimate));

        assertThat(impact.isEmpty()).isTrue();
    }

    @Test
    void volumeChangeIsReportedWithOldAndNewVolumeAndLabour() {
        EstimateEntity estimate = estimate();
        EstimateLineEntity line = line(estimate, tiling, "50.00");
        EstimateLineRoomQtyEntity cell = cell(line, room1, "10");
        EstimateSnapshot before = EstimateSnapshot.of(estimate);

        cell.setQuantity(new BigDecimal("12.5"));
        EstimateImpact impact = EstimateSnapshot.diff(before, EstimateSnapshot.of(estimate));

        assertThat(impact.changes()).hasSize(1);
        EstimateImpact.Change change = impact.changes().get(0);
        assertThat(change.kind()).isEqualTo(EstimateImpact.Kind.CHANGED);
        assertThat(change.workItemId()).isEqualTo(100L);
        assertThat(change.roomId()).isEqualTo(1L);
        assertThat(change.roomLabelPl()).isEqualTo("Łazienka"); // unlabeled room → room-type name
        assertThat(change.oldVolume()).isEqualByComparingTo("10");
        assertThat(change.newVolume()).isEqualByComparingTo("12.5");
        assertThat(change.oldLabour()).isEqualByComparingTo("500.00");
        assertThat(change.newLabour()).isEqualByComparingTo("625.00");
    }

    @Test
    void removedAndAddedCellsAreReported() {
        EstimateEntity estimate = estimate();
        EstimateLineEntity line = line(estimate, tiling, "50.00");
        EstimateLineRoomQtyEntity removed = cell(line, room1, "10");
        EstimateSnapshot before = EstimateSnapshot.of(estimate);

        line.getRoomQtys().remove(removed);
        cell(line, room2, "4");
        EstimateImpact impact = EstimateSnapshot.diff(before, EstimateSnapshot.of(estimate));

        assertThat(impact.changes()).extracting(EstimateImpact.Change::kind, EstimateImpact.Change::roomId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(EstimateImpact.Kind.REMOVED, 1L),
                        org.assertj.core.groups.Tuple.tuple(EstimateImpact.Kind.ADDED, 2L));
        EstimateImpact.Change added = impact.changes().stream()
                .filter(c -> c.kind() == EstimateImpact.Kind.ADDED).findFirst().orElseThrow();
        assertThat(added.oldVolume()).isNull();
        assertThat(added.newVolume()).isEqualByComparingTo("4");
        assertThat(added.roomLabelPl()).isEqualTo("Salon");
    }

    @Test
    void materialChangesAreCountedButOverriddenMaterialsAreStable() {
        EstimateEntity estimate = estimate();
        EstimateLineEntity line = line(estimate, tiling, "50.00");
        EstimateLineRoomQtyEntity cell = cell(line, room1, "10");
        material(cell, 1L, "1.5", false, null);       // norm × Volume → follows the Volume
        material(cell, 2L, "2.0", true, "33");        // manual quantity → unchanged
        EstimateSnapshot before = EstimateSnapshot.of(estimate);

        cell.setQuantity(new BigDecimal("20"));
        EstimateImpact impact = EstimateSnapshot.diff(before, EstimateSnapshot.of(estimate));

        assertThat(impact.changes()).singleElement()
                .satisfies(c -> assertThat(c.materialsChanged()).isEqualTo(1));
    }

    @Test
    void totalsAreCarriedFromTheEstimate() {
        EstimateEntity estimate = estimate();
        estimate.setTotalNet(new BigDecimal("100.00"));
        EstimateSnapshot before = EstimateSnapshot.of(estimate);
        estimate.setTotalNet(new BigDecimal("140.00"));

        EstimateImpact impact = EstimateSnapshot.diff(before, EstimateSnapshot.of(estimate));

        assertThat(impact.totalNetBefore()).isEqualByComparingTo("100.00");
        assertThat(impact.totalNetAfter()).isEqualByComparingTo("140.00");
    }

    // --- builders ---

    private static EstimateEntity estimate() {
        EstimateEntity estimate = new EstimateEntity();
        estimate.setId(1L);
        estimate.setTotalNet(BigDecimal.ZERO);
        return estimate;
    }

    private static EstimateLineEntity line(EstimateEntity estimate, WorkItemEntity work, String unitPrice) {
        EstimateLineEntity line = new EstimateLineEntity();
        line.setEstimate(estimate);
        line.setWorkItem(work);
        line.setUnitPrice(new BigDecimal(unitPrice));
        estimate.getLines().add(line);
        return line;
    }

    private static EstimateLineRoomQtyEntity cell(EstimateLineEntity line, RoomEntity room, String volume) {
        EstimateLineRoomQtyEntity cell = new EstimateLineRoomQtyEntity();
        cell.setLine(line);
        cell.setRoom(room);
        cell.setQuantity(new BigDecimal(volume));
        line.getRoomQtys().add(cell);
        return cell;
    }

    private static void material(EstimateLineRoomQtyEntity cell, Long id, String norm, boolean overridden, String manual) {
        EstimateLineRoomMaterialEntity material = new EstimateLineRoomMaterialEntity();
        material.setId(id);
        material.setRoomQty(cell);
        material.setNormQty(new BigDecimal(norm));
        material.setConsumptionBasis(ConsumptionBasis.PER_UNIT);
        material.setQtyOverridden(overridden);
        material.setManualQty(manual == null ? null : new BigDecimal(manual));
        cell.getMaterials().add(material);
    }

    private static RoomTypeEntity roomType(Long id, String ru, String pl) {
        RoomTypeEntity type = new RoomTypeEntity();
        type.setId(id);
        type.setNameRU(ru);
        type.setNamePL(pl);
        return type;
    }

    private static RoomEntity room(Long id, String label, RoomTypeEntity type) {
        RoomEntity room = new RoomEntity();
        room.setId(id);
        room.setLabel(label);
        room.setRoomType(type);
        return room;
    }

    private static WorkItemEntity work(Long id, String ru, String pl) {
        WorkItemEntity work = new WorkItemEntity();
        work.setId(id);
        work.setNameRU(ru);
        work.setNamePL(pl);
        return work;
    }
}
