package com.foremen.service.estimate.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
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
import com.foremen.service.estimate.CellAiMeta;

/** FOR-05-04 task 9.6 (Requirements 25.4, 25.5, 24.13): the matrix carries {@code aiSummary} and cell marks. */
@DisplayName("FOR-05-04 9.6: EstimateMatrixAssembler returns aiSummary and CellDto.ai")
class EstimateMatrixAssemblerAiSummaryTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long ROOM_ID = 7L;
    private static final Long WORK_ID = 3L;

    private final RoomDao roomDao = mock(RoomDao.class);
    private final WorkItemDao workItemDao = mock(WorkItemDao.class);
    private final WorkVolumeFormulaDao formulaDao = mock(WorkVolumeFormulaDao.class);
    private final WorkMaterialConsumptionDao consumptionDao = mock(WorkMaterialConsumptionDao.class);
    private final WorkPriceDao priceDao = mock(WorkPriceDao.class);
    private final EstimateMatrixAssembler assembler =
            new EstimateMatrixAssembler(roomDao, workItemDao, formulaDao, consumptionDao, priceDao);

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("aiSummary and the cell mark are returned; clearing the mark keeps the summary")
    void summaryAndMarkReturned() {
        EstimateEntity estimate = estimate();
        Map<String, Object> summary = Map.of("jobId", 9L, "generatedAt", "2026-01-01T10:00:00Z");
        estimate.setAiSummary(summary);

        EstimateMatrixDto matrix = assembler.assemble(estimate, PROJECT_ID, false);

        assertThat(matrix.aiSummary()).isEqualTo(summary);
        assertThat(matrix.groups().get(0).rows().get(0).cells().get(0).ai()).isNotNull();

        estimate.getLines().get(0).getRoomQtys().get(0).setAiMeta(null);
        EstimateMatrixDto cleared = assembler.assemble(estimate, PROJECT_ID, false);
        assertThat(cleared.groups().get(0).rows().get(0).cells().get(0).ai()).isNull();
        assertThat(cleared.aiSummary()).isEqualTo(summary);
    }

    @Test
    @DisplayName("an estimate with no AI run has aiSummary = null")
    void noSummaryIsNull() {
        EstimateMatrixDto matrix = assembler.assemble(estimate(), PROJECT_ID, false);

        assertThat(matrix.aiSummary()).isNull();
    }

    private EstimateEntity estimate() {
        LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag("pl"));
        RoomEntity room = new RoomEntity();
        room.setId(ROOM_ID);
        room.setLabel("room-1");
        MeasurementUnitEntity unit = new MeasurementUnitEntity();
        unit.setCode("m2");
        WorkCategoryEntity category = new WorkCategoryEntity();
        category.setId(100L);
        category.setOrderNo(1);
        WorkItemEntity work = new WorkItemEntity();
        work.setId(WORK_ID);
        work.setUnit(unit);
        work.setWorkCategory(category);
        when(roomDao.findByProjectId(PROJECT_ID)).thenReturn(List.of(room));
        when(workItemDao.findAll()).thenReturn(List.of(work));
        when(formulaDao.findAllWithWorkItem()).thenReturn(List.of());
        when(consumptionDao.findByWorkItemIdIn(any())).thenReturn(List.of());
        when(priceDao.findByWorkItemIdIn(any())).thenReturn(List.of());

        EstimateEntity estimate = new EstimateEntity();
        estimate.setId(1L);
        EstimateLineEntity line = new EstimateLineEntity();
        line.setId(10L);
        line.setWorkItem(work);
        line.setUnitPrice(new BigDecimal("10.00"));
        EstimateLineRoomQtyEntity cell = new EstimateLineRoomQtyEntity();
        cell.setId(20L);
        cell.setLine(line);
        cell.setRoom(room);
        cell.setQuantity(new BigDecimal("4"));
        cell.setAiMeta(new CellAiMeta("JOB", null, "ASSUMED", new BigDecimal("0.7"), null, "why", null, null, null));
        line.getRoomQtys().add(cell);
        estimate.getLines().add(line);
        return estimate;
    }
}
