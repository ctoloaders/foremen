package com.foremen.service.estimate.margins;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.ConstructionMaterialDao;
import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.WorkerTypeDao;
import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.service.EstimateService;
import com.foremen.service.estimate.margins.MarginsListAssembler.MaterialCostLookup;
import com.foremen.service.estimate.matrix.EstimateMatrixAssembler;
import com.foremen.service.estimate.matrix.EstimateMatrixDto;

/**
 * The Margins tab service (FOR-05-06, design §B5) — owns the tab's read-only cost/margin projection.
 *
 * <p>{@link #getMargins(Long)} resolves the project's estimate through the shipped get-or-create path
 * (so a pre-estimate project returns an empty structure, not a {@code 404}, mirroring
 * {@link com.foremen.service.estimate.materials.MaterialsListService#getMaterials}), obtains the
 * kosztorys matrix via the shipped {@link EstimateMatrixAssembler}, loads the ordered active
 * {@link WorkerTypeEntity} tiers and the material {@code cost_net} lookup, and delegates the whole
 * projection to the pure {@link MarginsListAssembler}. It is read-only and <b>independent of the DRAFT
 * lifecycle</b> — the tab always renders regardless of the estimate's writable state; nothing is
 * persisted (R4.1, R5.5).
 *
 * <p>The cost model itself lives in the pure {@link MarginCostService} / {@link MarginsListAssembler}
 * core (there is no second cost formula); this service only wires the read inputs (matrix + tiers +
 * material costs) into that core. WorkerType names are localized (RU/PL) from the request locale,
 * mirroring the read-path convention used by {@link EstimateMatrixAssembler}.
 */
@Service
public class MarginsListService {

    private final EstimateService estimateService;
    private final EstimateMatrixAssembler estimateMatrixAssembler;
    private final MarginsListAssembler marginsListAssembler;
    private final WorkerTypeDao workerTypeDao;
    private final ConstructionMaterialDao constructionMaterialDao;
    private final FinishingMaterialDao finishingMaterialDao;

    public MarginsListService(
            EstimateService estimateService,
            EstimateMatrixAssembler estimateMatrixAssembler,
            MarginsListAssembler marginsListAssembler,
            WorkerTypeDao workerTypeDao,
            ConstructionMaterialDao constructionMaterialDao,
            FinishingMaterialDao finishingMaterialDao) {
        this.estimateService = estimateService;
        this.estimateMatrixAssembler = estimateMatrixAssembler;
        this.marginsListAssembler = marginsListAssembler;
        this.workerTypeDao = workerTypeDao;
        this.constructionMaterialDao = constructionMaterialDao;
        this.finishingMaterialDao = finishingMaterialDao;
    }

    /**
     * Assemble the Margins tab read model for {@code projectId} (design §B5,
     * {@code GET /api/estimates/project/{projectId}/margins}). Read-only and independent of the DRAFT
     * lifecycle (R4.1): it resolves (or get-or-creates) the project's estimate — so a pre-estimate
     * project returns an empty structure instead of {@code 404} — obtains the kosztorys matrix via the
     * shipped {@link EstimateMatrixAssembler}, loads the ordered active WorkerType tiers and the
     * material {@code cost_net} lookup, and delegates the projection to the pure
     * {@link MarginsListAssembler}. Every offer, cost, and margin is derived from those inputs; nothing
     * per-project is persisted (R2.3, R5.5).
     *
     * <p>Because a first read may INSERT the estimate row (via the get-or-create path), this runs in a
     * read-write transaction, mirroring {@code EstimateAssignmentService.getMatrix} and
     * {@code MaterialsListService.getMaterials}.
     *
     * @param projectId the owning project whose margins to render
     * @return the assembled {@link MarginsListDto}
     */
    @Transactional
    public MarginsListDto getMargins(Long projectId) {
        EstimateEntity estimate = estimateService.getOrCreateEntityForProject(projectId);
        // The Margins tab is read-only; the matrix editable flag is irrelevant to the cost projection.
        EstimateMatrixDto matrix = estimateMatrixAssembler.assemble(estimate, projectId, false);
        List<WorkerTypeRefDto> workerTypes = loadWorkerTypes();
        MaterialCostLookup costLookup = loadMaterialCosts();
        return marginsListAssembler.assemble(matrix, workerTypes, costLookup);
    }

    /**
     * The ordered active WorkerType tiers mapped to {@link WorkerTypeRefDto} — the Margins matrix
     * column headers and per-tier cost inputs (R4.3). Ordered by {@code orderNo} then {@code id}
     * (see {@link WorkerTypeDao#findActiveOrdered()}); names localized (RU/PL) from the request locale.
     */
    private List<WorkerTypeRefDto> loadWorkerTypes() {
        boolean ru = isRussianLocale();
        List<WorkerTypeRefDto> refs = new ArrayList<>();
        for (WorkerTypeEntity tier : workerTypeDao.findActiveOrdered()) {
            String name = ru ? tier.getNameRU() : tier.getNamePL();
            refs.add(new WorkerTypeRefDto(tier.getId(), name, tier.isBase(), tier.getTierPct()));
        }
        return refs;
    }

    /**
     * The material {@code cost_net} lookup keyed by {@code (branch, concreteMaterialId)} (R4.4, R5.4).
     * Loads every active construction/finishing material's stored {@code cost_net} into a per-branch
     * map; a material whose id is not in its branch map (unknown/inactive) resolves to {@code null}, so
     * the assembler excludes that line's cost from the branch total (no fabricated value). A material
     * id can collide across branches, so the branch is part of the key.
     */
    private MaterialCostLookup loadMaterialCosts() {
        Map<Long, BigDecimal> constructionCosts = new HashMap<>();
        for (ConstructionMaterialEntity material : constructionMaterialDao.findAll()) {
            constructionCosts.put(material.getId(), material.getCostNet());
        }
        Map<Long, BigDecimal> finishingCosts = new HashMap<>();
        for (FinishingMaterialEntity material : finishingMaterialDao.findAll()) {
            finishingCosts.put(material.getId(), material.getCostNet());
        }
        return (branch, concreteMaterialId) -> {
            if (concreteMaterialId == null || branch == null) {
                return null;
            }
            Map<Long, BigDecimal> byBranch =
                    branch == ConsumptionBranch.construction ? constructionCosts : finishingCosts;
            return byBranch.get(concreteMaterialId);
        };
    }

    /** Whether the request locale is Russian — mirrors {@link EstimateMatrixAssembler}'s name choice. */
    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
