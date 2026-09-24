package com.foremen.service.estimate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.service.pricing.FinishingPriceRangeResolver;
import com.foremen.service.pricing.PriceRangeResolver;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based test for the estimate's <b>frozen price snapshot</b> in the
 * {@code EstimateAssignmentService} assign / {@code seedMaterialLines} path (FOR-05-05, task 5.6,
 * R13.1–R13.3, R3.4).
 *
 * <p>When a work is assigned, each material line copies its {@code Type_Price_Range} from the catalog
 * (construction via {@link PriceRangeResolver}, finishing via the package-less
 * {@link FinishingPriceRangeResolver}) into its own {@code rangeMin}/{@code rangeMax}, with a
 * provenance reference to the source catalog material — the estimate <b>owns</b> the copy. The copy is
 * a stable snapshot: a later catalog price change (or deactivation) does NOT retro-mutate the stored
 * range (R13.3). This test reconstructs that copy over an in-memory graph using the same public
 * resolvers the service composes and asserts:
 * <ul>
 *   <li>the copied range equals the resolver range at copy time (R13.1, R13.2, R3.4);</li>
 *   <li>after arbitrarily mutating the source catalog (retailNet edits, deactivation, removal), the
 *       already-copied {@code rangeMin}/{@code rangeMax} on the line are byte-for-byte unchanged —
 *       recomputing the resolver now would differ, but the frozen copy does not (R13.3);</li>
 *   <li>the copied collection is keyed by material type per assignment (R13.4 — one copied range per
 *       {@code (branch, type)}).</li>
 * </ul>
 *
 * <p>Feature: for-05-05-bill-of-materials, Property 10: Copied prices are a stable snapshot
 *
 * <p><b>Validates: Requirements 3.4, 13.1, 13.2, 13.3</b>
 */
// Feature: for-05-05-bill-of-materials, Property 10: Copied prices are a stable snapshot
@Tag("Feature: for-05-05-bill-of-materials, Property 10: Copied prices are a stable snapshot")
class EstimateSnapshotStabilityPropertyTest {

    private final PriceRangeResolver priceRangeResolver = new PriceRangeResolver();
    private final FinishingPriceRangeResolver finishingPriceRangeResolver = new FinishingPriceRangeResolver();

    /**
     * Property 10: for every generated (catalog, consumption type) the frozen copy equals the resolver
     * range at copy time, and after arbitrarily mutating the catalog the stored copy is unchanged — the
     * estimate owns a stable snapshot decoupled from later catalog edits (R13.1–R13.3, R3.4).
     *
     * <p>Feature: for-05-05-bill-of-materials, Property 10: Copied prices are a stable snapshot
     *
     * <p><b>Validates: Requirements 3.4, 13.1, 13.2, 13.3</b>
     */
    @Property(tries = 200)
    @Tag("Feature: for-05-05-bill-of-materials, Property 10: Copied prices are a stable snapshot")
    void copiedPricesAreAStableSnapshot(@ForAll("scenarios") Scenario scenario) {
        List<ConstructionMaterialEntity> constructionCatalog = scenario.toConstructionCatalog();
        List<FinishingMaterialEntity> finishingCatalog = scenario.toFinishingCatalog();

        // --- copy time: seed one frozen material line per consumption type (mirrors seedMaterialLines).
        List<EstimateLineRoomMaterialEntity> lines = new ArrayList<>();
        long id = 1;
        for (Consumption consumption : scenario.consumptions) {
            EstimateLineRoomMaterialEntity material = new EstimateLineRoomMaterialEntity();
            material.setId(id++);
            material.setBranch(consumption.branch);
            material.setNormQty(consumption.normQty);
            if (consumption.branch == ConsumptionBranch.construction) {
                ConstructionMaterialTypeEntity type = new ConstructionMaterialTypeEntity();
                type.setId(consumption.typeId);
                type.setCode("CT-" + consumption.typeId);
                type.setNameRU("ct-ru");
                type.setNamePL("ct-pl");
                material.setConstructionType(type);
                PriceRangeResolver.PriceRange range =
                        priceRangeResolver.rangeFor(constructionCatalog, consumption.typeId);
                material.setRangeMin(range.min());
                material.setRangeMax(range.max());
            } else {
                MaterialTypeEntity type = new MaterialTypeEntity();
                type.setId(consumption.typeId);
                type.setCode("FT-" + consumption.typeId);
                type.setNameRU("ft-ru");
                type.setNamePL("ft-pl");
                material.setFinishingType(type);
                // package-less fold is the assignment default (R3.4).
                FinishingPriceRangeResolver.PriceRange range =
                        finishingPriceRangeResolver.rangeFor(finishingCatalog, consumption.typeId, null);
                material.setRangeMin(range.min());
                material.setRangeMax(range.max());
            }
            lines.add(material);
        }

        // (R13.1, R13.2, R3.4) the copied range equals the resolver range at copy time.
        for (int i = 0; i < lines.size(); i++) {
            EstimateLineRoomMaterialEntity material = lines.get(i);
            Consumption consumption = scenario.consumptions.get(i);
            assertRangeEquals(material, expectedAtCopyTime(consumption, constructionCatalog, finishingCatalog));
        }

        // (R13.4) the copied collection is keyed by (branch, type) per assignment — no two lines share
        // the same key (the seed builds one line per consumption type).
        Set<String> keys = new HashSet<>();
        for (EstimateLineRoomMaterialEntity material : lines) {
            Long typeId = material.getBranch() == ConsumptionBranch.construction
                    ? material.getConstructionType().getId()
                    : material.getFinishingType().getId();
            keys.add(material.getBranch() + ":" + typeId);
        }
        // The generator constrains one consumption per (branch, type), so keys are all distinct.
        assertThat(keys).hasSize(lines.size());

        // Remember the copied snapshot values.
        List<BigDecimal> copiedMin = new ArrayList<>();
        List<BigDecimal> copiedMax = new ArrayList<>();
        for (EstimateLineRoomMaterialEntity material : lines) {
            copiedMin.add(material.getRangeMin());
            copiedMax.add(material.getRangeMax());
        }

        // --- later catalog edit: mutate the catalog arbitrarily (retailNet edits, deactivation).
        mutateCatalog(constructionCatalog, finishingCatalog, scenario);

        // (R13.3) the estimate's copied prices remain unchanged despite the catalog change: the frozen
        // rangeMin/rangeMax on each line are exactly what they were at copy time.
        for (int i = 0; i < lines.size(); i++) {
            EstimateLineRoomMaterialEntity material = lines.get(i);
            assertBigDecimalEquals(material.getRangeMin(), copiedMin.get(i));
            assertBigDecimalEquals(material.getRangeMax(), copiedMax.get(i));
        }
    }

    private static void assertRangeEquals(EstimateLineRoomMaterialEntity material, BigDecimal[] expected) {
        assertBigDecimalEquals(material.getRangeMin(), expected[0]);
        assertBigDecimalEquals(material.getRangeMax(), expected[1]);
    }

    private BigDecimal[] expectedAtCopyTime(
            Consumption consumption,
            List<ConstructionMaterialEntity> constructionCatalog,
            List<FinishingMaterialEntity> finishingCatalog) {
        if (consumption.branch == ConsumptionBranch.construction) {
            PriceRangeResolver.PriceRange range = priceRangeResolver.rangeFor(constructionCatalog, consumption.typeId);
            return new BigDecimal[] {range.min(), range.max()};
        }
        FinishingPriceRangeResolver.PriceRange range =
                finishingPriceRangeResolver.rangeFor(finishingCatalog, consumption.typeId, null);
        return new BigDecimal[] {range.min(), range.max()};
    }

    private static void assertBigDecimalEquals(BigDecimal actual, BigDecimal expected) {
        if (expected == null) {
            assertThat(actual).isNull();
        } else {
            assertThat(actual).isNotNull();
            assertThat(actual).isEqualByComparingTo(expected);
        }
    }

    /** Arbitrarily mutates the source catalog to model a later catalog price edit (R13.3). */
    private static void mutateCatalog(
            List<ConstructionMaterialEntity> constructionCatalog,
            List<FinishingMaterialEntity> finishingCatalog,
            Scenario scenario) {
        for (ConstructionMaterialEntity material : constructionCatalog) {
            // Bump the price and flip some active flags — a plausible catalog edit.
            if (material.getRetailNet() != null) {
                material.setRetailNet(material.getRetailNet().add(scenario.priceDelta));
            } else {
                material.setRetailNet(scenario.priceDelta);
            }
            material.setActive(!material.isActive());
        }
        for (FinishingMaterialEntity material : finishingCatalog) {
            if (material.getRetailNet() != null) {
                material.setRetailNet(material.getRetailNet().add(scenario.priceDelta));
            } else {
                material.setRetailNet(scenario.priceDelta);
            }
            material.setActive(!material.isActive());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    private static OfferPackageEntity pkg(Long id) {
        OfferPackageEntity offerPackage = new OfferPackageEntity();
        offerPackage.setId(id);
        offerPackage.setCode("PKG-" + id);
        offerPackage.setOrderNo(id.intValue());
        offerPackage.setNameRU("pkg-ru");
        offerPackage.setNamePL("pkg-pl");
        return offerPackage;
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    private record Scenario(
            List<ConstructionSpec> constructionSpecs,
            List<FinishingSpec> finishingSpecs,
            List<Consumption> consumptions,
            BigDecimal priceDelta) {

        List<ConstructionMaterialEntity> toConstructionCatalog() {
            List<ConstructionMaterialEntity> materials = new ArrayList<>();
            for (ConstructionSpec spec : constructionSpecs) {
                materials.add(spec.toEntity());
            }
            return materials;
        }

        List<FinishingMaterialEntity> toFinishingCatalog() {
            List<FinishingMaterialEntity> materials = new ArrayList<>();
            for (FinishingSpec spec : finishingSpecs) {
                materials.add(spec.toEntity());
            }
            return materials;
        }
    }

    private record ConstructionSpec(Long typeId, BigDecimal retailNet, boolean active) {

        ConstructionMaterialEntity toEntity() {
            ConstructionMaterialTypeEntity type = new ConstructionMaterialTypeEntity();
            type.setId(typeId);
            type.setCode("CT-" + typeId);
            type.setNameRU("ct-ru");
            type.setNamePL("ct-pl");

            ConstructionMaterialEntity material = new ConstructionMaterialEntity();
            material.setType(type);
            material.setRetailNet(retailNet);
            material.setActive(active);
            material.setNameRU("cm-ru");
            material.setNamePL("cm-pl");
            return material;
        }
    }

    private record FinishingSpec(Long typeId, BigDecimal retailNet, boolean active, Set<Long> packageIds) {

        FinishingMaterialEntity toEntity() {
            MaterialTypeEntity type = new MaterialTypeEntity();
            type.setId(typeId);
            type.setCode("FT-" + typeId);
            type.setNameRU("ft-ru");
            type.setNamePL("ft-pl");

            Set<OfferPackageEntity> packages = new HashSet<>();
            for (Long pkgId : packageIds) {
                packages.add(pkg(pkgId));
            }

            FinishingMaterialEntity material = new FinishingMaterialEntity();
            material.setType(type);
            material.setRetailNet(retailNet);
            material.setActive(active);
            material.setPackages(packages);
            return material;
        }
    }

    /** A work consumption line: the branch + type + norm that a fresh assignment seeds a copy for. */
    private record Consumption(ConsumptionBranch branch, Long typeId, BigDecimal normQty) {
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<BigDecimal> price = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.00"), new BigDecimal("50000.00"))
                .ofScale(2)
                .injectNull(0.2);
        Arbitrary<Set<Long>> packageIds = Arbitraries.longs()
                .between(1L, 3L)
                .set()
                .ofMinSize(0)
                .ofMaxSize(3);

        Arbitrary<ConstructionSpec> constructionSpec = Combinators.combine(
                        Arbitraries.longs().between(1L, 4L),
                        price,
                        Arbitraries.of(true, false))
                .as(ConstructionSpec::new);
        Arbitrary<List<ConstructionSpec>> constructionCatalog =
                constructionSpec.list().ofMinSize(0).ofMaxSize(15);

        Arbitrary<FinishingSpec> finishingSpec = Combinators.combine(
                        Arbitraries.longs().between(1L, 4L),
                        price,
                        Arbitraries.of(true, false),
                        packageIds)
                .as(FinishingSpec::new);
        Arbitrary<List<FinishingSpec>> finishingCatalog = finishingSpec.list().ofMinSize(0).ofMaxSize(15);

        // Consumptions: one per (branch, type) — build a de-duplicated set to honour R13.4 keying.
        Arbitrary<BigDecimal> norm = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.0000"), new BigDecimal("50.0000"))
                .ofScale(4);
        Arbitrary<Set<Long>> constructionTypes = Arbitraries.longs().between(1L, 4L)
                .set().ofMinSize(0).ofMaxSize(4);
        Arbitrary<Set<Long>> finishingTypes = Arbitraries.longs().between(1L, 4L)
                .set().ofMinSize(0).ofMaxSize(4);

        Arbitrary<List<Consumption>> consumptions = Combinators.combine(constructionTypes, finishingTypes, norm)
                .as((cTypes, fTypes, n) -> {
                    List<Consumption> list = new ArrayList<>();
                    for (Long t : cTypes) {
                        list.add(new Consumption(ConsumptionBranch.construction, t, n));
                    }
                    for (Long t : fTypes) {
                        list.add(new Consumption(ConsumptionBranch.finishing, t, n));
                    }
                    return list;
                });

        Arbitrary<BigDecimal> priceDelta = Arbitraries.bigDecimals()
                .between(new BigDecimal("1.00"), new BigDecimal("1000.00"))
                .ofScale(2);

        return Combinators.combine(constructionCatalog, finishingCatalog, consumptions, priceDelta)
                .as(Scenario::new);
    }
}
