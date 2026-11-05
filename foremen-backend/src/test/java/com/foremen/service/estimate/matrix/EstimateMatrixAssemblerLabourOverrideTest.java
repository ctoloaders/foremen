package com.foremen.service.estimate.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import com.foremen.dao.RoomDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkMaterialConsumptionDao;
import com.foremen.dao.WorkPriceDao;
import com.foremen.dao.WorkVolumeFormulaDao;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;

/**
 * FOR-05-05 Round 9 guard: the matrix cell carries BOTH the effective labour ({@code labour} =
 * {@code unit_price_override x Volume}) and the ORIGINAL labour ({@code baseLabour} =
 * {@code unit_price x Volume}), so the frontend can total the original and render the override's
 * discount/surcharge. With no override the two are equal; unassigned cells are zero.
 */
@DisplayName("EstimateMatrixAssembler — labour override: effective vs original labour per cell")
@Tag("Feature: FOR-05-05 Round 9, base labour on the matrix cell")
class EstimateMatrixAssemblerLabourOverrideTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long ROOM_ID = 7L;
    private static final Long WORK_ID = 3L;

    private final RoomDao roomDao = mock(RoomDao.class);
    private final WorkItemDao workItemDao = mock(WorkItemDao.class);
    private final WorkVolumeFormulaDao workVolumeFormulaDao = mock(WorkVolumeFormulaDao.class);
    private final WorkMaterialConsumptionDao workMaterialConsumptionDao = mock(WorkMaterialConsumptionDao.class);
    private final WorkPriceDao workPriceDao = mock(WorkPriceDao.class);

    private final EstimateMatrixAssembler assembler = new EstimateMatrixAssembler(
            roomDao, workItemDao, workVolumeFormulaDao, workMaterialConsumptionDao, workPriceDao);

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private void wireCatalog() {
        LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag("pl"));
        when(roomDao.findByProjectId(PROJECT_ID)).thenReturn(List.of(room()));
        when(workItemDao.findAll()).thenReturn(List.of(work()));
        when(workVolumeFormulaDao.findAllWithWorkItem()).thenReturn(List.of());
        lenient().when(workMaterialConsumptionDao.findByWorkItemIdIn(any())).thenReturn(List.of());
        lenient().when(workPriceDao.findByWorkItemIdIn(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("an override (+10%) raises labour but baseLabour stays on the original unit price")
    void overrideSplitsEffectiveAndOriginalLabour() {
        wireCatalog();
        CellDto cell = assembledCell(new BigDecimal("10.00"), new BigDecimal("11.00"), new BigDecimal("4"));

        assertThat(cell.labour()).isEqualByComparingTo("44.00");     // 11.00 x 4 (effective)
        assertThat(cell.baseLabour()).isEqualByComparingTo("40.00"); // 10.00 x 4 (original)
    }

    @Test
    @DisplayName("a discount override (-20%) lowers labour below the untouched baseLabour")
    void discountOverrideLowersLabourBelowBase() {
        wireCatalog();
        CellDto cell = assembledCell(new BigDecimal("10.00"), new BigDecimal("8.00"), new BigDecimal("5"));

        assertThat(cell.labour()).isEqualByComparingTo("40.00");
        assertThat(cell.baseLabour()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("with no override value the effective labour falls back to the original (labour == baseLabour)")
    void noOverrideLabourEqualsBase() {
        wireCatalog();
        CellDto cell = assembledCell(new BigDecimal("10.00"), null, new BigDecimal("3"));

        assertThat(cell.labour()).isEqualByComparingTo("30.00");
        assertThat(cell.baseLabour()).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("an unassigned cell has zero labour and zero baseLabour")
    void unassignedCellIsZero() {
        CellDto cell = CellDto.unassigned(WORK_ID, ROOM_ID);

        assertThat(cell.labour()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(cell.baseLabour()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("the 12-arg back-compat constructor defaults baseLabour to labour")
    void backCompatConstructorDefaultsBaseLabour() {
        CellDto cell = new CellDto(WORK_ID, ROOM_ID, true, BigDecimal.ONE, null, null, false, false,
                new BigDecimal("12.00"), List.of(), MoneyRange.ZERO, FillState.filled);

        assertThat(cell.baseLabour()).isEqualByComparingTo("12.00");
    }

    private CellDto assembledCell(BigDecimal unitPrice, BigDecimal override, BigDecimal volume) {
        EstimateEntity estimate = new EstimateEntity();
        estimate.setId(1L);

        EstimateLineEntity line = new EstimateLineEntity();
        line.setId(10L);
        line.setWorkItem(work());
        line.setUnitPrice(unitPrice);
        line.setUnitPriceOverride(override);

        EstimateLineRoomQtyEntity roomQty = new EstimateLineRoomQtyEntity();
        roomQty.setId(20L);
        roomQty.setLine(line);
        roomQty.setRoom(room());
        roomQty.setQuantity(volume);
        line.getRoomQtys().add(roomQty);
        estimate.getLines().add(line);

        EstimateMatrixDto matrix = assembler.assemble(estimate, PROJECT_ID, false);
        return matrix.groups().get(0).rows().get(0).cells().get(0);
    }

    private static RoomEntity room() {
        RoomEntity r = new RoomEntity();
        r.setId(ROOM_ID);
        r.setLabel("room-1");
        return r;
    }

    private static WorkItemEntity work() {
        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode("m2");
        unit.setNameRU("Кв. метр");
        unit.setNamePL("Metr kw.");
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setId(100L);
        category.setOrderNo(1);
        category.setNameRU("Категория");
        category.setNamePL("Kategoria");
        WorkItemEntity w = new WorkItemEntity();
        w.setId(WORK_ID);
        w.setNameRU("Работа");
        w.setNamePL("Praca");
        w.setUnit(unit);
        w.setWorkCategory(category);
        return w;
    }
}
