package com.foremen.service.procurement.estimatesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.foremen.dao.EstimateLineRoomMaterialDao;
import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.VatRateDao;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.PositionKind;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.EstimateService;
import com.foremen.service.estimate.materials.MaterialBranchGroupDto;
import com.foremen.service.estimate.materials.MaterialRowDto;
import com.foremen.service.estimate.materials.MaterialsListDto;
import com.foremen.service.estimate.materials.MaterialsListService;
import com.foremen.service.estimate.materials.PlaceholderRowDto;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.planner.ScheduledCategoryService;
import com.foremen.service.procurement.planner.WorkConsumptionLookup;

/**
 * FOR-11-06 (R5.1 to R5.8): estimate sync algorithm with mocked collaborators.
 * Validates: Requirements 5.1, 5.2, 5.4, 5.5, 5.6, 5.8
 */
@ExtendWith(MockitoExtension.class)
class EstimateSyncServiceTest {

    private static final long PROJECT_ID = 7L;

    @Mock MaterialsListService materialsListService;
    @Mock EstimateService estimateService;
    @Mock EstimateLineRoomMaterialDao bomDao;
    @Mock PurchasePositionDao positionDao;
    @Mock ProjectDao projectDao;
    @Mock FinishingMaterialDao materialDao;
    @Mock MeasurementUnitDao unitDao;
    @Mock VatRateDao vatRateDao;
    @Mock ScheduledCategoryService scheduledCategoryService;
    @Mock WorkConsumptionLookup consumptionLookup;
    @Mock ProcurementProjectAccess projectAccess;

    private EstimateSyncService service;
    private ProjectEntity project;
    private EstimateEntity estimate;
    private MeasurementUnitEntity m2;

    @BeforeEach
    void setUp() {
        service = new EstimateSyncService(materialsListService, estimateService, bomDao, positionDao, projectDao,
                materialDao, unitDao, vatRateDao, scheduledCategoryService, consumptionLookup, projectAccess);
        project = new ProjectEntity();
        project.setId(PROJECT_ID);
        project.setStatus(ProjectStatus.ACTIVE);
        estimate = new EstimateEntity();
        estimate.setId(100L);
        m2 = new MeasurementUnitEntity();
        m2.setId(1L);
        m2.setCode("m2");

        lenient().when(projectDao.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        lenient().when(estimateService.getOrCreateEntityForProject(PROJECT_ID)).thenReturn(estimate);
        lenient().when(bomDao.findByEstimateIdAndBranch(100L, ConsumptionBranch.finishing)).thenReturn(List.of());
        lenient().when(positionDao.findEstimateDerivedByProjectId(PROJECT_ID)).thenReturn(List.of());
        lenient().when(scheduledCategoryService.scheduled(PROJECT_ID)).thenReturn(Map.of());
        lenient().when(vatRateDao.findAll()).thenReturn(List.of());
        lenient().when(unitDao.findByCode("m2")).thenReturn(Optional.of(m2));
        lenient().when(materialsListService.placeholders(PROJECT_ID)).thenReturn(List.of());
        lenient().when(materialsListService.getMaterials(PROJECT_ID, false)).thenReturn(list());
    }

    private static MaterialsListDto list(MaterialBranchGroupDto... groups) {
        return new MaterialsListDto(PROJECT_ID, false, List.of(), List.of(groups), List.of(), null, null);
    }

    private static MaterialRowDto materialRow(long materialId, String qty, String net) {
        return new MaterialRowDto(materialId, "mat", 3L, "type", ConsumptionBranch.finishing, null, "m2",
                new BigDecimal(net), null, List.of(), null, null, new BigDecimal(qty), null);
    }

    private static PlaceholderRowDto placeholder(long typeId, String qty) {
        return new PlaceholderRowDto(typeId, "type", "m2", new BigDecimal(qty), null, null);
    }

    private FinishingMaterialEntity material(long id, ProjectEntity owner) {
        FinishingMaterialEntity m = new FinishingMaterialEntity();
        m.setId(id);
        m.setProject(owner);
        m.setUnit(m2);
        m.setPurchasePrice(new BigDecimal("4.00"));
        return m;
    }

    private PurchasePositionEntity existing(String key, PositionStatus status, String qty) {
        PurchasePositionEntity p = new PurchasePositionEntity();
        p.setProject(project);
        p.setKind(PositionKind.FINISHING);
        p.setStatus(status);
        p.setEstimateSourceKey(key);
        p.setQuantity(new BigDecimal(qty));
        p.setUnit(m2);
        return p;
    }

    @Test
    void firstRunCreatesPlannedFinishingPositionsForMaterialAndPlaceholderRows() {
        when(materialsListService.getMaterials(PROJECT_ID, false)).thenReturn(list(new MaterialBranchGroupDto(
                ConsumptionBranch.finishing, List.of(materialRow(5L, "12", "9.50")))));
        when(materialsListService.placeholders(PROJECT_ID)).thenReturn(List.of(placeholder(8L, "3")));
        when(materialDao.findById(5L)).thenReturn(Optional.of(material(5L, project)));

        EstimateSyncSummary summary = service.sync(PROJECT_ID);

        assertEquals(new EstimateSyncSummary(2, 0, 0, 0), summary);
        ArgumentCaptor<PurchasePositionEntity> captor = ArgumentCaptor.forClass(PurchasePositionEntity.class);
        verify(positionDao, times(2)).save(captor.capture());
        PurchasePositionEntity withMaterial = captor.getAllValues().get(0);
        assertEquals("M:5", withMaterial.getEstimateSourceKey());
        assertEquals(PositionStatus.PLANNED, withMaterial.getStatus());
        assertEquals(PositionKind.FINISHING, withMaterial.getKind());
        assertEquals(0, withMaterial.getQuantity().compareTo(new BigDecimal("12")));
        assertEquals(0, withMaterial.getSalePrice().compareTo(new BigDecimal("9.50")));
        assertEquals(0, withMaterial.getPlannedPurchasePrice().compareTo(new BigDecimal("4.00")));
        assertNull(withMaterial.getNeedByDate());
        PurchasePositionEntity pending = captor.getAllValues().get(1);
        assertEquals("T:8", pending.getEstimateSourceKey());
        assertNull(pending.getPlannedMaterial());
        assertNull(pending.getSalePrice());
    }

    @Test
    void secondRunWithSameEstimateCreatesNothingAndReportsUnchanged() {
        when(materialsListService.getMaterials(PROJECT_ID, false)).thenReturn(list(new MaterialBranchGroupDto(
                ConsumptionBranch.finishing, List.of(materialRow(5L, "12", "9.50")))));
        when(materialsListService.placeholders(PROJECT_ID)).thenReturn(List.of(placeholder(8L, "3")));
        FinishingMaterialEntity mat = material(5L, project);
        when(materialDao.findById(5L)).thenReturn(Optional.of(mat));
        PurchasePositionEntity a = existing("M:5", PositionStatus.PLANNED, "12");
        a.setPlannedMaterial(mat);
        a.setSalePrice(new BigDecimal("9.50"));
        a.setPlannedPurchasePrice(new BigDecimal("4.00"));
        PurchasePositionEntity b = existing("T:8", PositionStatus.PLANNED, "3");
        when(positionDao.findEstimateDerivedByProjectId(PROJECT_ID)).thenReturn(List.of(a, b));

        EstimateSyncSummary summary = service.sync(PROJECT_ID);

        assertEquals(new EstimateSyncSummary(0, 0, 2, 0), summary);
        assertFalse(a.isEstimateRemoved());
        assertFalse(b.isEstimateChanged());
    }

    @Test
    void plannedPositionIsUpdatedWhenEstimateQuantityChanges() {
        when(materialsListService.placeholders(PROJECT_ID)).thenReturn(List.of(placeholder(8L, "5")));
        PurchasePositionEntity b = existing("T:8", PositionStatus.PLANNED, "3");
        when(positionDao.findEstimateDerivedByProjectId(PROJECT_ID)).thenReturn(List.of(b));

        EstimateSyncSummary summary = service.sync(PROJECT_ID);

        assertEquals(new EstimateSyncSummary(0, 1, 0, 0), summary);
        assertEquals(0, b.getQuantity().compareTo(new BigDecimal("5")));
    }

    @Test
    void positionWhoseRowDisappearedIsFlaggedRemovedAndNeverDeleted() {
        PurchasePositionEntity gone = existing("T:9", PositionStatus.PROPOSED, "2");
        PurchasePositionEntity cancelled = existing("T:10", PositionStatus.CANCELLED, "2");
        when(positionDao.findEstimateDerivedByProjectId(PROJECT_ID)).thenReturn(List.of(gone, cancelled));

        EstimateSyncSummary summary = service.sync(PROJECT_ID);

        assertEquals(new EstimateSyncSummary(0, 0, 0, 1), summary);
        assertTrue(gone.isEstimateRemoved());
        assertFalse(cancelled.isEstimateRemoved());
        verify(positionDao, never()).delete(any(PurchasePositionEntity.class));
        verify(positionDao, never()).deleteById(any());
    }

    @Test
    void constructionRowsAreIgnored() {
        when(materialsListService.getMaterials(PROJECT_ID, false)).thenReturn(list(new MaterialBranchGroupDto(
                ConsumptionBranch.construction, List.of(materialRow(5L, "12", "9.50")))));

        EstimateSyncSummary summary = service.sync(PROJECT_ID);

        assertEquals(new EstimateSyncSummary(0, 0, 0, 0), summary);
        verify(positionDao, never()).save(any());
    }

    @Test
    void nonActiveProjectIsRejectedWithConflictAndNothingIsWritten() {
        project.setStatus(ProjectStatus.DRAFT);

        ForemenApiException ex = assertThrows(ForemenApiException.class, () -> service.sync(PROJECT_ID));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(EstimateSyncService.PROJECT_NOT_ACTIVE, ex.getMessageCode());
        verify(positionDao, never()).save(any());
    }

    @Test
    void unknownProjectIsNotFound() {
        when(projectDao.findById(PROJECT_ID)).thenReturn(Optional.empty());

        ForemenApiException ex = assertThrows(ForemenApiException.class, () -> service.sync(PROJECT_ID));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void bomRowOfAnotherEstimateIsRejected() {
        EstimateEntity other = new EstimateEntity();
        other.setId(999L);
        EstimateLineEntity line = new EstimateLineEntity();
        line.setEstimate(other);
        EstimateLineRoomQtyEntity roomQty = new EstimateLineRoomQtyEntity();
        roomQty.setLine(line);
        EstimateLineRoomMaterialEntity bom = new EstimateLineRoomMaterialEntity();
        bom.setId(55L);
        bom.setBranch(ConsumptionBranch.finishing);
        bom.setRoomQty(roomQty);
        when(bomDao.findByEstimateIdAndBranch(100L, ConsumptionBranch.finishing)).thenReturn(List.of(bom));

        ForemenApiException ex = assertThrows(ForemenApiException.class, () -> service.sync(PROJECT_ID));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verify(positionDao, never()).save(any());
    }

    @Test
    void materialOfAnotherProjectIsRejected() {
        ProjectEntity other = new ProjectEntity();
        other.setId(8L);
        when(materialsListService.getMaterials(PROJECT_ID, false)).thenReturn(list(new MaterialBranchGroupDto(
                ConsumptionBranch.finishing, List.of(materialRow(5L, "12", "9.50")))));
        when(materialDao.findById(5L)).thenReturn(Optional.of(material(5L, other)));

        ForemenApiException ex = assertThrows(ForemenApiException.class, () -> service.sync(PROJECT_ID));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals(EstimateSyncService.MATERIAL_PROJECT_MISMATCH, ex.getMessageCode());
    }

    @Test
    void callerWithoutProjectAccessGetsNotFound() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();

        ForemenApiException ex = assertThrows(ForemenApiException.class, () -> service.syncForCaller(PROJECT_ID));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verify(projectDao, never()).findById(any());
    }
}
