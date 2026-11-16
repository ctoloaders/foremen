package com.foremen.service.procurement.estimatesync;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.EstimateLineRoomMaterialDao;
import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.VatRateDao;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.PositionKind;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.EstimateService;
import com.foremen.service.estimate.materials.MaterialBranchGroupDto;
import com.foremen.service.estimate.materials.MaterialRowDto;
import com.foremen.service.estimate.materials.MaterialsListDto;
import com.foremen.service.estimate.materials.MaterialsListService;
import com.foremen.service.estimate.materials.PlaceholderRowDto;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.estimatesync.EstimateSyncReconciler.Outcome;
import com.foremen.service.procurement.estimatesync.EstimateSyncReconciler.Target;
import com.foremen.service.procurement.planner.ScheduledCategoryService;
import com.foremen.service.procurement.planner.WorkCategoryResolver;
import com.foremen.service.procurement.planner.WorkCategoryResolver.Resolution;
import com.foremen.service.procurement.planner.WorkCategoryResolver.Scheduled;
import com.foremen.service.procurement.planner.WorkConsumptionLookup;

import lombok.RequiredArgsConstructor;

/**
 * FOR-11-06 (R5.1 to R5.8, design 6.2): keeps one {@code PurchasePosition} per finishing row of the
 * project's materials list. Idempotent: rows are matched by {@code (project, estimate_source_key)}.
 *
 * <p>The sync never deletes a position. The work-category link is derived (not stored); {@code needByDate}
 * is filled from the start of the earliest scheduled work resolved by {@link WorkCategoryResolver}, and
 * {@code orderByDate} stays as the planner leaves it.
 */
@Service
@RequiredArgsConstructor
public class EstimateSyncService {

    static final String ENTITY_NOT_FOUND = "error.entity.not.found";
    static final String PROJECT_NOT_ACTIVE = "error.purchasePosition.project.notActive";
    static final String MATERIAL_PROJECT_MISMATCH = "error.purchasePosition.material.projectMismatch";

    private static final BigDecimal DEFAULT_PURCHASE_VAT = new BigDecimal("23");
    private static final BigDecimal DEFAULT_SALE_VAT = new BigDecimal("8");

    private final MaterialsListService materialsListService;
    private final EstimateService estimateService;
    private final EstimateLineRoomMaterialDao bomDao;
    private final PurchasePositionDao positionDao;
    private final ProjectDao projectDao;
    private final FinishingMaterialDao materialDao;
    private final MeasurementUnitDao unitDao;
    private final VatRateDao vatRateDao;
    private final ScheduledCategoryService scheduledCategoryService;
    private final WorkConsumptionLookup consumptionLookup;
    private final ProcurementProjectAccess projectAccess;

    private final WorkCategoryResolver categoryResolver = new WorkCategoryResolver();

    /** On-demand entry point: checks the caller's project access, then syncs. */
    @Transactional
    public EstimateSyncSummary syncForCaller(Long projectId) {
        requireProjectAccess(projectId);
        return sync(projectId);
    }

    /** Activation entry point: its own transaction so an after-commit caller can write. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EstimateSyncSummary syncInNewTransaction(Long projectId) {
        return sync(projectId);
    }

    @Transactional
    public EstimateSyncSummary sync(Long projectId) {
        ProjectEntity project = projectDao.findById(projectId).orElseThrow(() -> new ForemenApiException(
                HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, "projectId", projectId));
        if (project.getStatus() != ProjectStatus.ACTIVE) {
            throw new ForemenApiException(HttpStatus.CONFLICT, PROJECT_NOT_ACTIVE);
        }
        EstimateEntity estimate = estimateService.getOrCreateEntityForProject(projectId);
        Map<String, EstimateLineRoomMaterialEntity> bomByKey = bomRows(estimate);
        List<EstimateRow> rows = rows(projectId);

        Map<String, PurchasePositionEntity> existing = new LinkedHashMap<>();
        for (PurchasePositionEntity p : positionDao.findEstimateDerivedByProjectId(projectId)) {
            existing.put(p.getEstimateSourceKey(), p);
        }
        Map<Long, Scheduled> scheduled = scheduledCategoryService.scheduled(projectId);
        Map<Long, Set<Long>> tier1 = scheduled.isEmpty() ? Map.of()
                : consumptionLookup.estimateCategoriesByType(projectId);
        VatRateEntity purchaseVat = vatRate(DEFAULT_PURCHASE_VAT);
        VatRateEntity saleVat = vatRate(DEFAULT_SALE_VAT);

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int flagged = 0;
        Set<String> seen = new HashSet<>();
        for (EstimateRow row : rows) {
            if (!seen.add(row.sourceKey())) {
                continue;
            }
            FinishingMaterialEntity material = row.materialId() == null
                    ? null : requireMaterial(row.materialId(), projectId);
            MeasurementUnitEntity unit = resolveUnit(row.unitCode(), material);
            if (unit == null) {
                continue;
            }
            LocalDate needBy = needByDate(row, material, scheduled, tier1);
            BigDecimal purchasePrice = material == null ? null : material.getPurchasePrice();
            PurchasePositionEntity position = existing.get(row.sourceKey());
            if (position == null) {
                positionDao.save(create(project, row, material, unit, needBy, purchasePrice,
                        bomByKey.get(row.sourceKey()), purchaseVat, saleVat));
                created++;
                continue;
            }
            Outcome outcome = EstimateSyncReconciler.reconcile(position,
                    new Target(row.quantity(), unit, row.materialId(), row.salePrice(), purchasePrice, needBy));
            if (position.getEstimateReference() == null && bomByKey.get(row.sourceKey()) != null) {
                position.setEstimateReference(bomByKey.get(row.sourceKey()));
            }
            positionDao.save(position);
            switch (outcome) {
                case UPDATED -> updated++;
                case FLAGGED -> flagged++;
                default -> unchanged++;
            }
        }
        for (Map.Entry<String, PurchasePositionEntity> entry : existing.entrySet()) {
            PurchasePositionEntity position = entry.getValue();
            if (seen.contains(entry.getKey()) || position.getStatus() == PositionStatus.CANCELLED) {
                continue;
            }
            position.setEstimateRemoved(true);
            positionDao.save(position);
            flagged++;
        }
        return new EstimateSyncSummary(created, updated, unchanged, flagged);
    }

    /** Finishing rows: chosen-material rows first, then placeholders; construction rows are ignored (R5.8). */
    List<EstimateRow> rows(Long projectId) {
        List<EstimateRow> rows = new ArrayList<>();
        MaterialsListDto list = materialsListService.getMaterials(projectId, false);
        if (list != null && list.branches() != null) {
            for (MaterialBranchGroupDto group : list.branches()) {
                if (group.branch() != ConsumptionBranch.finishing || group.rows() == null) {
                    continue;
                }
                for (MaterialRowDto row : group.rows()) {
                    if (row.materialId() == null) {
                        continue;
                    }
                    rows.add(new EstimateRow(row.sourceKey(), row.materialId(), row.typeId(), row.unit(),
                            row.effectiveTotalQty(), row.netUnitPrice()));
                }
            }
        }
        for (PlaceholderRowDto row : materialsListService.placeholders(projectId)) {
            rows.add(new EstimateRow(row.sourceKey(), null, row.finishingTypeId(), row.unit(), row.qty(), null));
        }
        rows.removeIf(r -> r.quantity() == null || r.quantity().signum() <= 0);
        return rows;
    }

    /** The project's finishing BoM rows by source key; a row of another estimate or branch is rejected (R5.8). */
    private Map<String, EstimateLineRoomMaterialEntity> bomRows(EstimateEntity estimate) {
        Map<String, EstimateLineRoomMaterialEntity> result = new LinkedHashMap<>();
        for (EstimateLineRoomMaterialEntity bom : bomDao.findByEstimateIdAndBranch(
                estimate.getId(), ConsumptionBranch.finishing)) {
            if (bom.getBranch() != ConsumptionBranch.finishing
                    || !Objects.equals(bom.getRoomQty().getLine().getEstimate().getId(), estimate.getId())) {
                throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND,
                        "estimateReferenceId", bom.getId());
            }
            String key = bom.getConcreteFinishingMaterial() != null
                    ? "M:" + bom.getConcreteFinishingMaterial().getId()
                    : bom.getFinishingType() != null ? "T:" + bom.getFinishingType().getId() : null;
            if (key != null) {
                result.putIfAbsent(key, bom);
            }
        }
        return result;
    }

    private PurchasePositionEntity create(ProjectEntity project, EstimateRow row, FinishingMaterialEntity material,
                                          MeasurementUnitEntity unit, LocalDate needBy, BigDecimal purchasePrice,
                                          EstimateLineRoomMaterialEntity bom, VatRateEntity purchaseVat, VatRateEntity saleVat) {
        PurchasePositionEntity e = new PurchasePositionEntity();
        e.setProject(project);
        e.setKind(PositionKind.FINISHING);
        e.setSourcingPath(SourcingPath.AGREED);
        e.setStatus(PositionStatus.PLANNED);
        e.setPlannedMaterial(material);
        e.setQuantity(row.quantity());
        e.setUnit(unit);
        e.setSalePrice(row.salePrice());
        e.setPlannedPurchasePrice(purchasePrice);
        e.setNeedByDate(needBy);
        e.setEstimateSourceKey(row.sourceKey());
        e.setEstimateReference(bom);
        e.setPurchaseVatRate(purchaseVat);
        e.setSaleVatRate(saleVat);
        return e;
    }

    private LocalDate needByDate(EstimateRow row, FinishingMaterialEntity material, Map<Long, Scheduled> scheduled,
                                 Map<Long, Set<Long>> tier1) {
        Long typeId = material != null && material.getType() != null ? material.getType().getId() : row.typeId();
        if (typeId == null || scheduled.isEmpty()) {
            return null;
        }
        Set<Long> matched = new HashSet<>(tier1.getOrDefault(typeId, Set.of()));
        matched.retainAll(scheduled.keySet());
        Map<Long, Set<Long>> tier2 = matched.isEmpty()
                ? consumptionLookup.catalogCategoriesByType(Set.of(typeId)) : Map.of();
        Resolution resolution = categoryResolver.resolve(null, typeId, tier1, tier2, scheduled);
        Scheduled hit = resolution.categoryId() == null ? null : scheduled.get(resolution.categoryId());
        return hit == null ? null : hit.startDate();
    }

    private FinishingMaterialEntity requireMaterial(Long materialId, Long projectId) {
        FinishingMaterialEntity material = materialDao.findById(materialId).orElseThrow(
                () -> new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, "materialId", materialId));
        if (material.getProject() != null && !Objects.equals(material.getProject().getId(), projectId)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, MATERIAL_PROJECT_MISMATCH);
        }
        return material;
    }

    private MeasurementUnitEntity resolveUnit(String code, FinishingMaterialEntity material) {
        if (code != null) {
            MeasurementUnitEntity byCode = unitDao.findByCode(code).orElse(null);
            if (byCode != null) {
                return byCode;
            }
        }
        return material == null ? null : material.getUnit();
    }

    private VatRateEntity vatRate(BigDecimal rate) {
        VatRateEntity inactive = null;
        for (VatRateEntity r : vatRateDao.findAll()) {
            if (r.getRate() != null && r.getRate().compareTo(rate) == 0) {
                if (r.isActive()) {
                    return r;
                }
                inactive = r;
            }
        }
        return inactive;
    }

    private void requireProjectAccess(Long projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, projectId);
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(ga.getAuthority()) || "ADMIN".equals(ga.getAuthority())) {
                return;
            }
        }
        Long userId;
        try {
            userId = Long.parseLong(auth.getName().trim());
        } catch (RuntimeException e) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, projectId);
        }
        Set<Long> allowed = projectAccess.allowedProjectIds(userId);
        if (allowed == null || !allowed.contains(projectId)) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND, projectId);
        }
    }
}
