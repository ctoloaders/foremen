package com.foremen.service.pricing;

import com.foremen.dao.ConstructionMaterialDao;
import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.OfferPackageEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DAO-backed provider of the analog {@link TypeBatch}es that feed the FOR-04-19 computed money range
 * (вилка). It keeps the database read OUT of {@code MaterialRangeResolver} so the resolver stays a
 * pure, deterministic function of already-loaded data (Requirement 4.2, 4.6).
 *
 * <p>For a set of {@code (offerPackageId, materialTypeId, branch)} keys, {@link #load(Collection)}
 * loads the analog batch's non-null {@code retailNet}s in <b>ONE query per branch</b>:
 * <ul>
 *   <li><b>construction</b>: {@code construction_materials} ⋈ {@code construction_material_packages}
 *       (via {@link ConstructionMaterialDao#findByActiveTrueAndRetailNetNotNull()} — an
 *       {@code EntityGraph} fetching {@code packages} + {@code type}), filtered to {@code active = true}
 *       and a non-null {@code retailNet};</li>
 *   <li><b>finishing</b>: {@code finishing_materials} ⋈ {@code finishing_material_packages}
 *       (via {@link FinishingMaterialDao#findByActiveTrueAndRetailNetNotNull()}), same filter.</li>
 * </ul>
 * A branch whose keys are not requested is not queried at all. Each qualifying material is fanned out
 * to {@code (offerPackage.id, type.id)} for every package in its {@code packages} set, and the batch's
 * {@code retailNet}s are collected per pair. Every requested key gets exactly one {@link TypeBatch} in
 * the result — with an EMPTY {@code retailNets} list when the type has no priced material in the
 * package (Requirement 4.6), which the resolver interprets as a {@code 0..0} batch range.
 *
 * <p>The lookup performs no computation on the prices (no MIN/MAX, no {@code normQty} multiplication):
 * it only gathers the raw {@code retailNet}s so the pure resolver can aggregate them.
 */
@Component
public class MaterialBatchLookup {

    /** The lookup key: an analog batch is identified by its {@code (package, type, branch)} triple. */
    public record BatchKey(Long offerPackageId, Long materialTypeId, ConsumptionBranch branch) {
    }

    private final ConstructionMaterialDao constructionMaterialDao;
    private final FinishingMaterialDao finishingMaterialDao;

    public MaterialBatchLookup(ConstructionMaterialDao constructionMaterialDao,
                               FinishingMaterialDao finishingMaterialDao) {
        this.constructionMaterialDao = constructionMaterialDao;
        this.finishingMaterialDao = finishingMaterialDao;
    }

    /**
     * Loads the analog {@link TypeBatch} for each requested {@code (package, type, branch)} key.
     *
     * <p>At most one query is issued per branch, and only for the branches actually present in
     * {@code keys}. Every requested key is represented exactly once in the returned map; a key with
     * no priced material in its package yields a {@link TypeBatch} carrying an empty
     * {@code retailNets} list.
     *
     * @param keys the analog-batch keys to resolve (may be {@code null} or empty)
     * @return a map from each requested key to its {@link TypeBatch}
     */
    @Transactional(readOnly = true)
    public Map<BatchKey, TypeBatch> load(Collection<BatchKey> keys) {
        Map<BatchKey, TypeBatch> result = new LinkedHashMap<>();
        if (keys == null || keys.isEmpty()) {
            return result;
        }

        boolean needConstruction = false;
        boolean needFinishing = false;
        for (BatchKey key : keys) {
            if (key == null || key.branch() == null) {
                continue;
            }
            if (key.branch() == ConsumptionBranch.construction) {
                needConstruction = true;
            } else if (key.branch() == ConsumptionBranch.finishing) {
                needFinishing = true;
            }
        }

        // One query per requested branch; group the batch's retailNets by (packageId, typeId).
        Map<PackageType, List<BigDecimal>> constructionBatches =
                needConstruction ? loadConstructionBatches() : Map.of();
        Map<PackageType, List<BigDecimal>> finishingBatches =
                needFinishing ? loadFinishingBatches() : Map.of();

        for (BatchKey key : keys) {
            if (key == null || key.branch() == null
                    || key.offerPackageId() == null || key.materialTypeId() == null) {
                continue;
            }
            Map<PackageType, List<BigDecimal>> batches =
                    key.branch() == ConsumptionBranch.construction ? constructionBatches : finishingBatches;
            List<BigDecimal> retailNets = batches.getOrDefault(
                    new PackageType(key.offerPackageId(), key.materialTypeId()), List.of());
            result.put(key, new TypeBatch(
                    key.offerPackageId(), key.materialTypeId(), key.branch(), List.copyOf(retailNets)));
        }

        return result;
    }

    private Map<PackageType, List<BigDecimal>> loadConstructionBatches() {
        Map<PackageType, List<BigDecimal>> batches = new HashMap<>();
        for (ConstructionMaterialEntity material : constructionMaterialDao.findByActiveTrueAndRetailNetNotNull()) {
            if (material == null || material.getRetailNet() == null
                    || material.getType() == null || material.getType().getId() == null
                    || material.getPackages() == null) {
                continue;
            }
            addToBatches(batches, material.getType().getId(), material.getRetailNet(), material.getPackages());
        }
        return batches;
    }

    private Map<PackageType, List<BigDecimal>> loadFinishingBatches() {
        Map<PackageType, List<BigDecimal>> batches = new HashMap<>();
        for (FinishingMaterialEntity material : finishingMaterialDao.findByActiveTrueAndRetailNetNotNull()) {
            if (material == null || material.getRetailNet() == null
                    || material.getType() == null || material.getType().getId() == null
                    || material.getPackages() == null) {
                continue;
            }
            addToBatches(batches, material.getType().getId(), material.getRetailNet(), material.getPackages());
        }
        return batches;
    }

    private void addToBatches(Map<PackageType, List<BigDecimal>> batches,
                              Long typeId,
                              BigDecimal retailNet,
                              Collection<OfferPackageEntity> packages) {
        for (OfferPackageEntity offerPackage : packages) {
            if (offerPackage == null || offerPackage.getId() == null) {
                continue;
            }
            batches.computeIfAbsent(new PackageType(offerPackage.getId(), typeId), k -> new ArrayList<>())
                    .add(retailNet);
        }
    }

    /** Grouping key within a single branch: the pair (offer package id, material type id). */
    private record PackageType(Long offerPackageId, Long materialTypeId) {
    }
}
