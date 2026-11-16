package com.foremen.service.estimate.materials;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.foremen.dao.model.ConsumptionBasis;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.service.estimate.matrix.BranchSubtotals;
import com.foremen.service.estimate.matrix.CellDto;
import com.foremen.service.estimate.matrix.EstimateMatrixDto;
import com.foremen.service.estimate.matrix.EstimateMatrixRoomDto;
import com.foremen.service.estimate.matrix.FillState;
import com.foremen.service.estimate.matrix.MaterialLineDto;
import com.foremen.service.estimate.matrix.MoneyRange;
import com.foremen.service.estimate.matrix.WorkRowDto;
import com.foremen.service.estimate.matrix.WorkTypeGroupDto;

/** FOR-11-06 task 4.1: placeholder rows and the stable source key of the Materials list. */
class MaterialsListPlaceholdersTest {

    private static MaterialLineDto line(ConsumptionBranch branch, Long typeId, Long concreteId, String unit,
            String qty, String min, String max, ConsumptionBasis basis) {
        return new MaterialLineDto(
                1L, branch, typeId, "type-" + typeId, BigDecimal.ONE,
                new BigDecimal(min), new BigDecimal(max),
                concreteId, concreteId == null ? null : "mat-" + concreteId,
                concreteId == null ? null : new BigDecimal("5.00"), unit,
                new BigDecimal(qty), false, basis, false, null, false);
    }

    private static EstimateMatrixDto matrix(MaterialLineDto... lines) {
        CellDto cell = new CellDto(
                1L, 10L, true, BigDecimal.ONE, "floorArea", "floorArea", false, false,
                BigDecimal.ZERO, List.of(lines), MoneyRange.ZERO, FillState.filled);
        WorkRowDto row = new WorkRowDto(1L, "work", List.of(), List.of(cell), BigDecimal.ZERO, MoneyRange.ZERO);
        WorkTypeGroupDto group = new WorkTypeGroupDto(
                1L, "cat", List.of(row), BranchSubtotals.ZERO, BigDecimal.ZERO, MoneyRange.ZERO);
        return new EstimateMatrixDto(
                1L, true, List.of(new EstimateMatrixRoomDto(10L, "room", null, null)),
                List.of(group), BranchSubtotals.ZERO, new BigDecimal("100.00"), BigDecimal.ZERO,
                MoneyRange.ZERO, null);
    }

    @Test
    void placeholdersAreFinishingLinesWithoutChosenMaterialFoldedByType() {
        EstimateMatrixDto matrix = matrix(
                line(ConsumptionBranch.finishing, 7L, null, "m2", "2.10", "10.00", "20.00", ConsumptionBasis.PER_UNIT),
                line(ConsumptionBranch.finishing, 7L, null, "m2", "1.10", "8.00", "25.00", ConsumptionBasis.PER_UNIT),
                line(ConsumptionBranch.finishing, 8L, 99L, "m2", "4.00", "1.00", "2.00", ConsumptionBasis.PER_UNIT),
                line(ConsumptionBranch.construction, 9L, null, "kg", "4.00", "1.00", "2.00", ConsumptionBasis.PER_UNIT));

        List<PlaceholderRowDto> rows = MaterialsListAssembler.placeholders(matrix);

        assertThat(rows).hasSize(1);
        PlaceholderRowDto row = rows.get(0);
        assertThat(row.sourceKey()).isEqualTo("T:7");
        assertThat(row.qty()).isEqualByComparingTo("4"); // ceil(2.10 + 1.10) = ceil(3.2)
        assertThat(row.rangeMin()).isEqualByComparingTo("8.00");
        assertThat(row.rangeMax()).isEqualByComparingTo("25.00");
        assertThat(row.unit()).isEqualTo("m2");
    }

    @Test
    void perRoomPlaceholderKeepsExactQuantityAndPiecesRoundUp() {
        List<PlaceholderRowDto> perRoom = MaterialsListAssembler.placeholders(matrix(
                line(ConsumptionBranch.finishing, 7L, null, "m2", "1.50", "1", "2", ConsumptionBasis.PER_ROOM)));
        assertThat(perRoom.get(0).qty()).isEqualByComparingTo("1.50");

        List<PlaceholderRowDto> pieces = MaterialsListAssembler.placeholders(matrix(
                line(ConsumptionBranch.finishing, 7L, null, "szt", "1.50", "1", "2", ConsumptionBasis.PER_ROOM)));
        assertThat(pieces.get(0).qty()).isEqualByComparingTo("2");
    }

    @Test
    void chosenMaterialRowKeyIsMaterialId() {
        MaterialsListDto dto = MaterialsListAssembler.project(matrix(
                line(ConsumptionBranch.finishing, 8L, 99L, "m2", "4.00", "1", "2", ConsumptionBasis.PER_UNIT)),
                new BigDecimal("23"), null, false);
        MaterialRowDto row = dto.branches().stream()
                .flatMap(b -> b.rows().stream()).findFirst().orElseThrow();
        assertThat(row.sourceKey()).isEqualTo("M:99");
    }

    @Test
    void nullMatrixHasNoPlaceholders() {
        assertThat(MaterialsListAssembler.placeholders(null)).isEmpty();
    }
}
