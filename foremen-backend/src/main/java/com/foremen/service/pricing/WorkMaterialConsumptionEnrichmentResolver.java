package com.foremen.service.pricing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.controller.model.AnalogMaterialDto;
import com.foremen.controller.model.MoneyRangeDto;
import com.foremen.controller.model.RefDto;
import com.foremen.dao.ConstructionMaterialDao;
import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialProducerEntity;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.service.pricing.MaterialRangeResolver.MoneyRange;

/**
 * Enriches the FOR-04-19 consumption READ path so the drill-in returns real per-material data
 * (Requirement 5.4). For one {@code WorkMaterialConsumption} row it resolves the two computed,
 * read-time-only fields the {@code WorkMaterialConsumptionServiceMapper} leaves unset:
 *
 * <ol>
 *   <li><b>{@code typeBatchRange}</b> — the type-level money band {@code normQty × [MIN..MAX
 *       retailNet]} over the row's analog batch (the active materials of the row's TYPE in the row's
 *       offer package), computed via {@link MaterialRangeResolver#typeBatchRange}. An empty/unpriced
 *       batch collapses to an explicit {@code 0..0} rather than {@code null} so a missing catalog
 *       item stays visible (Requirement 4.2, 4.6).</li>
 *   <li><b>{@code materials}</b> — the analog batch itself: EVERY active material of the row's type
 *       in the package (priced or not), mapped to {@link AnalogMaterialDto} with the material's
 *       localized name/producer/seller/unit, its {@code retailNet}, and its per-material money cost
 *       {@code moneyCost = normQty × retailNet} ({@code null} when the material is unpriced).</li>
 * </ol>
 *
 * <p>The full analog materials are loaded through a targeted, index-friendly DAO query
 * ({@code findActiveByTypes}) filtered by the row's material {@code type} — package-less per
 * FOR-05-04 Requirement 7.2, since {@code WorkMaterialConsumption} no longer carries an
 * {@code offerPackage} dimension — and eagerly fetching {@code producer}/{@code seller}/
 * {@code unit}/{@code type}, so a single row's enrichment issues at most one query per branch (no
 * lazy-load per material). The type-level range uses the SAME loaded batch's non-null
 * {@code retailNet}s, so the band and the per-material costs are always consistent for the row.
 *
 * <p>These fields are read-time computed and NEVER persisted or audited (they are absent from the
 * {@code WorkMaterialConsumptionService.serializeEntity} snapshot). Producer/seller/unit names are
 * localized per the request locale (RU when the request language is {@code ru}, else PL — PL
 * fallback), consistent with the existing consumption mappers.
 */
@Component
public class WorkMaterialConsumptionEnrichmentResolver {

    private final ConstructionMaterialDao constructionMaterialDao;
    private final FinishingMaterialDao finishingMaterialDao;

    public WorkMaterialConsumptionEnrichmentResolver(ConstructionMaterialDao constructionMaterialDao,
                                                     FinishingMaterialDao finishingMaterialDao) {
        this.constructionMaterialDao = constructionMaterialDao;
        this.finishingMaterialDao = finishingMaterialDao;
    }

    /**
     * The enrichment result for one consumption row: the computed type-level money band and the
     * analog batch's materials (both derived from the same loaded batch).
     *
     * @param typeBatchRange the type-level band {@code normQty × [MIN..MAX retailNet]} (never
     *                       {@code null}; {@code 0..0} for an empty/unpriced batch)
     * @param materials      the analog batch as {@link AnalogMaterialDto}s (never {@code null}; empty
     *                       when the type has no material in the package)
     */
    public record Enrichment(MoneyRangeDto typeBatchRange, List<AnalogMaterialDto> materials) {
    }

    /**
     * Resolves the {@code typeBatchRange} and analog {@code materials} for one consumption row.
     *
     * <p>Returns an explicit {@code 0..0} band and an empty material list when the row is malformed
     * (no branch / no matching type id / no offer package) or when the type has no active material in
     * the package, so the drill-in still renders the row with a visible {@code 0} cost.
     *
     * @param entity the consumption row (with its {@code offerPackage}, {@code branch} and the set
     *               material-type reference initialized)
     * @return the enrichment (never {@code null})
     */
    @Transactional(readOnly = true)
    public Enrichment resolve(WorkMaterialConsumptionEntity entity) {
        if (entity == null) {
            return empty();
        }
        ConsumptionBranch branch = entity.getBranch();
        Long typeId = materialTypeId(entity);
        BigDecimal normQty = entity.getNormQty();
        if (branch == null || typeId == null) {
            return empty();
        }

        boolean ru = isRussianLocale();
        List<AnalogMaterialDto> materials = new ArrayList<>();
        List<BigDecimal> retailNets = new ArrayList<>();

        if (branch == ConsumptionBranch.construction) {
            for (ConstructionMaterialEntity material :
                    constructionMaterialDao.findActiveByTypes(List.of(typeId))) {
                if (material == null) {
                    continue;
                }
                BigDecimal retailNet = material.getRetailNet();
                if (retailNet != null) {
                    retailNets.add(retailNet);
                }
                materials.add(new AnalogMaterialDto(
                        material.getId(),
                        localizedName(ru, material.getNameRU(), material.getNamePL()),
                        producerName(ru, material.getProducer()),
                        sellerName(ru, material.getSeller()),
                        unitRef(ru, material.getUnit()),
                        retailNet,
                        moneyCost(normQty, retailNet)));
            }
        } else if (branch == ConsumptionBranch.finishing) {
            for (FinishingMaterialEntity material :
                    finishingMaterialDao.findActiveByTypes(List.of(typeId))) {
                if (material == null) {
                    continue;
                }
                BigDecimal retailNet = material.getRetailNet();
                if (retailNet != null) {
                    retailNets.add(retailNet);
                }
                materials.add(new AnalogMaterialDto(
                        material.getId(),
                        finishingName(ru, material),
                        producerName(ru, material.getProducer()),
                        null, // finishing materials have no seller
                        unitRef(ru, material.getUnit()),
                        retailNet,
                        moneyCost(normQty, retailNet)));
            }
        }

        // The band uses the SAME batch's priced retailNets, so it is consistent with the per-material
        // costs above; an empty/unpriced batch yields the explicit 0..0 (Requirement 4.2, 4.6).
        TypeBatch batch = new TypeBatch(typeId, branch, retailNets);
        MoneyRange range = MaterialRangeResolver.typeBatchRange(normQty, batch);
        return new Enrichment(new MoneyRangeDto(range.min(), range.max()), materials);
    }

    private static Enrichment empty() {
        return new Enrichment(new MoneyRangeDto(BigDecimal.ZERO, BigDecimal.ZERO), new ArrayList<>());
    }

    private static BigDecimal moneyCost(BigDecimal normQty, BigDecimal retailNet) {
        if (normQty == null || retailNet == null) {
            return null;
        }
        // Same normalization as the band: derived money shown to 2 decimals, rounded UP (grosz).
        return MaterialRangeResolver.money(normQty.multiply(retailNet));
    }

    /** Whichever of the two material-type references is set (XOR at the write path), or null. */
    private static Long materialTypeId(WorkMaterialConsumptionEntity entity) {
        if (entity.getBranch() == ConsumptionBranch.construction) {
            return entity.getConstructionMaterialType() == null
                    ? null : entity.getConstructionMaterialType().getId();
        }
        if (entity.getBranch() == ConsumptionBranch.finishing) {
            return entity.getFinishingMaterialType() == null
                    ? null : entity.getFinishingMaterialType().getId();
        }
        return null;
    }

    private static String finishingName(boolean ru, FinishingMaterialEntity material) {
        if (material.getMaterial() == null) {
            return null;
        }
        return localizedName(ru, material.getMaterial().getNameRU(), material.getMaterial().getNamePL());
    }

    private static String producerName(boolean ru, MaterialProducerEntity producer) {
        return producer == null ? null : localizedName(ru, producer.getNameRU(), producer.getNamePL());
    }

    private static String sellerName(boolean ru, MaterialSellerEntity seller) {
        return seller == null ? null : localizedName(ru, seller.getNameRU(), seller.getNamePL());
    }

    private static RefDto unitRef(boolean ru, MeasurementUnitEntity unit) {
        return unit == null ? null : new RefDto(unit.getId(), localizedName(ru, unit.getNameRU(), unit.getNamePL()));
    }

    private static String localizedName(boolean ru, String nameRU, String namePL) {
        return ru ? nameRU : namePL;
    }

    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
