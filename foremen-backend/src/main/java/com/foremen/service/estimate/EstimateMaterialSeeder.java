package com.foremen.service.estimate;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.foremen.dao.EstimateLineRoomMaterialDao;
import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConsumptionBasis;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.service.pricing.FinishingPriceRangeResolver;
import com.foremen.service.pricing.PriceRangeResolver;

/**
 * Seeds the frozen material lines of a freshly created estimate cell (FOR-05-05 R3.4, R13.2;
 * FOR-05-04-estimate-AI task 3.2). Extracted unchanged from {@code EstimateAssignmentService} so the
 * assign path and the AI scratch replay share one implementation.
 *
 * <p>One {@link EstimateLineRoomMaterialEntity} is created per consumption of the work, copying the
 * norm, the consumption basis and the {@code Type_Price_Range}: construction ranges via
 * {@link PriceRangeResolver}, finishing ranges via the package-less {@link FinishingPriceRangeResolver}
 * (the widest honest band, R3.4).
 */
@Component
public class EstimateMaterialSeeder {

    private final EstimateLineRoomMaterialDao estimateLineRoomMaterialDao;
    private final PriceRangeResolver priceRangeResolver;
    private final FinishingPriceRangeResolver finishingPriceRangeResolver;

    public EstimateMaterialSeeder(
            EstimateLineRoomMaterialDao estimateLineRoomMaterialDao,
            PriceRangeResolver priceRangeResolver,
            FinishingPriceRangeResolver finishingPriceRangeResolver) {
        this.estimateLineRoomMaterialDao = estimateLineRoomMaterialDao;
        this.priceRangeResolver = priceRangeResolver;
        this.finishingPriceRangeResolver = finishingPriceRangeResolver;
    }

    /**
     * Seeds one material line per consumption onto {@code roomQty}.
     *
     * @param consumptions the work's consumptions (batch-loaded by the caller to avoid an N+1)
     * @param constructionCatalog lazily supplies the active priced construction materials (called at
     *        most once, only when a construction consumption is seeded)
     * @param finishingCatalog lazily supplies the active priced finishing materials (same contract)
     */
    public void seed(
            EstimateLineRoomQtyEntity roomQty,
            List<WorkMaterialConsumptionEntity> consumptions,
            Supplier<Collection<ConstructionMaterialEntity>> constructionCatalog,
            Supplier<Collection<FinishingMaterialEntity>> finishingCatalog) {
        seed(roomQty, consumptions, constructionCatalog, finishingCatalog, null);
    }

    /**
     * As {@link #seed(EstimateLineRoomQtyEntity, List, Supplier, Supplier)} with the offer package scoping
     * the finishing price ranges (FOR-05-04 R23.8; {@code null} = the package-less widest band of a manual
     * assign).
     *
     * @param finishingPackageId the offer package id, or {@code null} for no package
     */
    public void seed(
            EstimateLineRoomQtyEntity roomQty,
            List<WorkMaterialConsumptionEntity> consumptions,
            Supplier<Collection<ConstructionMaterialEntity>> constructionCatalog,
            Supplier<Collection<FinishingMaterialEntity>> finishingCatalog,
            Long finishingPackageId) {
        if (consumptions == null || consumptions.isEmpty()) {
            return;
        }

        Collection<ConstructionMaterialEntity> constructionMaterials = null;
        Collection<FinishingMaterialEntity> finishingMaterials = null;

        for (WorkMaterialConsumptionEntity consumption : consumptions) {
            EstimateLineRoomMaterialEntity material = new EstimateLineRoomMaterialEntity();
            material.setRoomQty(roomQty);
            material.setBranch(consumption.getBranch());
            material.setNormQty(consumption.getNormQty());
            // Copy the consumption basis onto the frozen line so the estimate is self-contained (#4).
            material.setConsumptionBasis(consumption.getConsumptionBasis() != null
                    ? consumption.getConsumptionBasis() : ConsumptionBasis.PER_UNIT);

            if (consumption.getBranch() == ConsumptionBranch.construction
                    && consumption.getConstructionMaterialType() != null) {
                Long typeId = consumption.getConstructionMaterialType().getId();
                material.setConstructionType(consumption.getConstructionMaterialType());
                if (constructionMaterials == null) {
                    constructionMaterials = constructionCatalog.get();
                }
                PriceRangeResolver.PriceRange range = priceRangeResolver.rangeFor(constructionMaterials, typeId);
                material.setRangeMin(range.min());
                material.setRangeMax(range.max());
            } else if (consumption.getBranch() == ConsumptionBranch.finishing
                    && consumption.getFinishingMaterialType() != null) {
                Long typeId = consumption.getFinishingMaterialType().getId();
                material.setFinishingType(consumption.getFinishingMaterialType());
                if (finishingMaterials == null) {
                    finishingMaterials = finishingCatalog.get();
                }
                // Assignment default is the package-less fold across all packages (R3.4).
                FinishingPriceRangeResolver.PriceRange range =
                        finishingPriceRangeResolver.rangeFor(finishingMaterials, typeId, finishingPackageId);
                if (finishingPackageId != null && range.min() == null && range.max() == null) {
                    // The package has no price for this type: fall back to the package-less band.
                    range = finishingPriceRangeResolver.rangeFor(finishingMaterials, typeId, null);
                }
                material.setRangeMin(range.min());
                material.setRangeMax(range.max());
            } else {
                continue; // malformed consumption (branch/type mismatch) -> skip, do not seed a row
            }

            estimateLineRoomMaterialDao.save(material);
            roomQty.getMaterials().add(material);
        }
    }
}
