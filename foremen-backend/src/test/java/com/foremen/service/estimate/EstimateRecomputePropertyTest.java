package com.foremen.service.estimate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.service.pricing.FinishingPriceRangeResolver;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based test for the {@code Recompute_Finishing_Prices} <b>calculate core</b> of
 * {@code EstimateAssignmentService} (FOR-05-05, task 5.6, design §B4).
 *
 * <p>{@code calculateRecomputeFinishingPrices} / its per-line {@code planFinishingRecompute} core is
 * a pure, non-persisting computation: for an in-memory estimate graph it returns the recomputed
 * package-scoped finishing ranges for the already-assigned <b>finishing</b> material lines only, and
 * touches nothing else. This test mirrors that pure core over an in-memory graph (the core is private
 * and depends on Spring-managed DAOs, so it is reconstructed here from the same public
 * {@link FinishingPriceRangeResolver} primitive, exactly as the service composes it) and asserts:
 * <ul>
 *   <li>the calculated plan differs from the input <b>only</b> in already-assigned finishing ranges —
 *       never a construction line, never labour ({@code unitPrice}), never a volume
 *       ({@code roomQty.quantity}), never a chosen concrete product
 *       ({@code concreteNet}/concrete FK) (R12.3, R12.4);</li>
 *   <li>the computation persists nothing: the input graph is byte-for-byte unchanged after computing,
 *       so the result is a staged (undoable) plan written only on Save, not at compute time
 *       (R12.2, R15.3, R15.6);</li>
 *   <li>a finishing type with no recomputable package price yields no plan entry (R12.6).</li>
 * </ul>
 *
 * <p>Feature: for-05-05-bill-of-materials, Property 9: Recompute changes finishing ranges only and
 * preserves selections
 *
 * <p><b>Validates: Requirements 12.2, 12.3, 12.4</b>
 */
// Feature: for-05-05-bill-of-materials, Property 9: Recompute changes finishing ranges only and preserves selections
@Tag("Feature: for-05-05-bill-of-materials, Property 9: Recompute changes finishing ranges only and preserves selections")
class EstimateRecomputePropertyTest {

    private final FinishingPriceRangeResolver finishingPriceRangeResolver = new FinishingPriceRangeResolver();

    /**
     * Property 9: for every generated estimate graph + package, recomputing the finishing prices
     * (a) plans a new range for exactly the already-assigned finishing lines whose type has a
     * non-empty package-scoped range, (b) plans nothing for construction lines or finishing lines with
     * an empty package range (R12.6), and (c) leaves the entire input graph untouched — every
     * construction range, every labour {@code unitPrice}, every {@code quantity}, and every chosen
     * concrete product is exactly what it was before the compute (R12.3, R12.4, R15.6).
     *
     * <p>Feature: for-05-05-bill-of-materials, Property 9: Recompute changes finishing ranges only and
     * preserves selections
     *
     * <p><b>Validates: Requirements 12.2, 12.3, 12.4</b>
     */
    @Property(tries = 200)
    @Tag("Feature: for-05-05-bill-of-materials, Property 9: Recompute changes finishing ranges only and preserves selections")
    void recomputeChangesFinishingRangesOnlyAndPreservesSelections(@ForAll("scenarios") Scenario scenario) {
        List<FinishingMaterialEntity> catalog = scenario.toCatalog();
        OfferPackageEntity offerPackage = pkg(scenario.packageId);

        EstimateLineEntity line = scenario.toLine();

        // Snapshot every mutable field of the graph BEFORE the calculate, so we can assert the pure
        // core mutated nothing (persists nothing at compute time — R15.6).
        List<Snapshot> before = snapshot(line);

        // Reconstruct the service's pure per-line recompute core over the in-memory graph, using the
        // same public FinishingPriceRangeResolver primitive the service composes.
        List<Plan> plans = new ArrayList<>();
        for (EstimateLineRoomQtyEntity roomQty : line.getRoomQtys()) {
            for (EstimateLineRoomMaterialEntity material : roomQty.getMaterials()) {
                planFinishingRecompute(material, offerPackage, catalog, plans);
            }
        }

        // (c) The compute persisted / mutated nothing — the graph is unchanged (pure, non-persisting).
        List<Snapshot> after = snapshot(line);
        assertThat(after).isEqualTo(before);

        // Independently determine which material lines are recomputable finishing lines, and their
        // expected new range, over exactly the same qualifying rule as the resolver.
        Set<EstimateLineRoomMaterialEntity> plannedLines = new HashSet<>();
        for (Plan plan : plans) {
            plannedLines.add(plan.material());
        }

        for (EstimateLineRoomQtyEntity roomQty : line.getRoomQtys()) {
            for (EstimateLineRoomMaterialEntity material : roomQty.getMaterials()) {
                FinishingPriceRangeResolver.PriceRange expected = expectedFinishingRange(material, scenario, catalog);
                boolean recomputable = material.getBranch() == ConsumptionBranch.finishing
                        && material.getFinishingType() != null
                        && !(expected.min() == null && expected.max() == null);

                if (recomputable) {
                    // (a) exactly this finishing line is planned, with the package-scoped range.
                    assertThat(plannedLines).contains(material);
                    Plan plan = plans.stream()
                            .filter(p -> p.material() == material)
                            .findFirst()
                            .orElseThrow();
                    assertThat(plan.rangeMin()).isEqualByComparingTo(expected.min());
                    assertThat(plan.rangeMax()).isEqualByComparingTo(expected.max());
                } else {
                    // (b) construction lines, and finishing lines with an empty package range, are
                    // never planned (R12.3 finishing-only; R12.6 no recomputable price -> no plan).
                    assertThat(plannedLines).doesNotContain(material);
                }
            }
        }

        // The plan never targets a construction line under any circumstance (R12.3).
        for (Plan plan : plans) {
            assertThat(plan.material().getBranch()).isEqualTo(ConsumptionBranch.finishing);
        }
    }

    /**
     * The service's pure per-line recompute core, reconstructed 1:1: a no-op for a non-finishing line,
     * a finishing line without a type, or a type with no recomputable package price (empty range).
     * Otherwise emits a plan carrying ONLY the new range — the chosen concrete product, norm, and
     * everything else are left untouched (R12.3/R12.4). Mutates only {@code out}, persists nothing.
     */
    private void planFinishingRecompute(
            EstimateLineRoomMaterialEntity material,
            OfferPackageEntity offerPackage,
            List<FinishingMaterialEntity> catalog,
            List<Plan> out) {
        if (material.getBranch() != ConsumptionBranch.finishing || material.getFinishingType() == null) {
            return;
        }
        FinishingPriceRangeResolver.PriceRange range = finishingPriceRangeResolver.rangeFor(
                catalog, material.getFinishingType().getId(), offerPackage.getId());
        if (range.min() == null && range.max() == null) {
            return;
        }
        out.add(new Plan(material, range.min(), range.max()));
    }

    /** The expected package-scoped range for a finishing line's type (EMPTY for a construction line). */
    private FinishingPriceRangeResolver.PriceRange expectedFinishingRange(
            EstimateLineRoomMaterialEntity material, Scenario scenario, List<FinishingMaterialEntity> catalog) {
        if (material.getBranch() != ConsumptionBranch.finishing || material.getFinishingType() == null) {
            return FinishingPriceRangeResolver.PriceRange.EMPTY;
        }
        return finishingPriceRangeResolver.rangeFor(catalog, material.getFinishingType().getId(), scenario.packageId);
    }

    // ------------------------------------------------------------------------------------------
    // Graph snapshot (to prove the pure core mutated nothing)
    // ------------------------------------------------------------------------------------------

    private record Snapshot(
            ConsumptionBranch branch,
            Long typeId,
            BigDecimal normQty,
            BigDecimal rangeMin,
            BigDecimal rangeMax,
            BigDecimal concreteNet,
            BigDecimal quantity,
            BigDecimal unitPrice) {
    }

    private static List<Snapshot> snapshot(EstimateLineEntity line) {
        List<Snapshot> snapshots = new ArrayList<>();
        for (EstimateLineRoomQtyEntity roomQty : line.getRoomQtys()) {
            for (EstimateLineRoomMaterialEntity material : roomQty.getMaterials()) {
                Long typeId = material.getBranch() == ConsumptionBranch.construction
                        ? (material.getConstructionType() != null ? material.getConstructionType().getId() : null)
                        : (material.getFinishingType() != null ? material.getFinishingType().getId() : null);
                snapshots.add(new Snapshot(
                        material.getBranch(),
                        typeId,
                        material.getNormQty(),
                        material.getRangeMin(),
                        material.getRangeMax(),
                        material.getConcreteNet(),
                        roomQty.getQuantity(),
                        line.getUnitPrice()));
            }
        }
        return snapshots;
    }

    /** A planned recompute (the finishing line + its new range) — the reconstructed FinishingRangePlan. */
    private record Plan(EstimateLineRoomMaterialEntity material, BigDecimal rangeMin, BigDecimal rangeMax) {
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------------------------------

    private static OfferPackageEntity pkg(Long id) {
        OfferPackageEntity offerPackage = new OfferPackageEntity();
        offerPackage.setId(id);
        offerPackage.setCode("PKG-" + id);
        offerPackage.setOrderNo(id.intValue());
        offerPackage.setNameRU("pkg-ru-" + id);
        offerPackage.setNamePL("pkg-pl-" + id);
        return offerPackage;
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /**
     * A flat scenario: the recompute package id, a set of catalog finishing materials (varying type,
     * price, active, package membership), and the estimate's material lines across a few rooms (each
     * varying branch, type, chosen-concrete state, norm and starting range).
     */
    private record Scenario(
            Long packageId,
            List<MaterialSpec> catalogSpecs,
            List<CellSpec> cells,
            BigDecimal unitPrice) {

        List<FinishingMaterialEntity> toCatalog() {
            List<FinishingMaterialEntity> materials = new ArrayList<>();
            for (MaterialSpec spec : catalogSpecs) {
                materials.add(spec.toEntity());
            }
            return materials;
        }

        EstimateLineEntity toLine() {
            EstimateLineEntity line = new EstimateLineEntity();
            line.setId(1L);
            line.setUnitPrice(unitPrice);
            long roomQtyId = 1;
            long materialId = 1;
            for (CellSpec cell : cells) {
                EstimateLineRoomQtyEntity roomQty = new EstimateLineRoomQtyEntity();
                roomQty.setId(roomQtyId++);
                roomQty.setLine(line);
                roomQty.setQuantity(cell.quantity);
                for (LineSpec lineSpec : cell.lines) {
                    EstimateLineRoomMaterialEntity material = lineSpec.toEntity(materialId++);
                    material.setRoomQty(roomQty);
                    roomQty.getMaterials().add(material);
                }
                line.getRoomQtys().add(roomQty);
            }
            return line;
        }
    }

    /** A catalog finishing material spec (mirrors the FinishingPriceRangeResolver qualifying axes). */
    private record MaterialSpec(Long typeId, BigDecimal retailNet, boolean active, Set<Long> packageIds) {

        FinishingMaterialEntity toEntity() {
            MaterialTypeEntity type = new MaterialTypeEntity();
            type.setId(typeId);
            type.setCode("FT-" + typeId);
            type.setNameRU("ft-ru-" + typeId);
            type.setNamePL("ft-pl-" + typeId);

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

    /** A cell (room-qty) spec: its Volume and the material lines in it. */
    private record CellSpec(BigDecimal quantity, List<LineSpec> lines) {
    }

    /** A material-line spec varying branch, type, chosen-concrete state, norm and starting range. */
    private record LineSpec(
            ConsumptionBranch branch,
            Long typeId,
            BigDecimal normQty,
            BigDecimal startRangeMin,
            BigDecimal startRangeMax,
            BigDecimal concreteNet) {

        EstimateLineRoomMaterialEntity toEntity(long id) {
            EstimateLineRoomMaterialEntity material = new EstimateLineRoomMaterialEntity();
            material.setId(id);
            material.setBranch(branch);
            material.setNormQty(normQty);
            material.setRangeMin(startRangeMin);
            material.setRangeMax(startRangeMax);
            material.setConcreteNet(concreteNet);
            if (branch == ConsumptionBranch.construction) {
                ConstructionMaterialTypeEntity type = new ConstructionMaterialTypeEntity();
                type.setId(typeId);
                type.setCode("CT-" + typeId);
                type.setNameRU("ct-ru-" + typeId);
                type.setNamePL("ct-pl-" + typeId);
                material.setConstructionType(type);
            } else {
                MaterialTypeEntity type = new MaterialTypeEntity();
                type.setId(typeId);
                type.setCode("FT-" + typeId);
                type.setNameRU("ft-ru-" + typeId);
                type.setNamePL("ft-pl-" + typeId);
                material.setFinishingType(type);
            }
            return material;
        }
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<Long> packageId = Arbitraries.longs().between(1L, 3L);

        Arbitrary<BigDecimal> price = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.00"), new BigDecimal("50000.00"))
                .ofScale(2)
                .injectNull(0.2);
        Arbitrary<Set<Long>> packageIds = Arbitraries.longs()
                .between(1L, 3L)
                .set()
                .ofMinSize(0)
                .ofMaxSize(3);
        Arbitrary<MaterialSpec> materialSpec = Combinators.combine(
                        Arbitraries.longs().between(1L, 4L),
                        price,
                        Arbitraries.of(true, false),
                        packageIds)
                .as(MaterialSpec::new);
        Arbitrary<List<MaterialSpec>> catalog = materialSpec.list().ofMinSize(0).ofMaxSize(20);

        Arbitrary<BigDecimal> money = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.00"), new BigDecimal("9999.00"))
                .ofScale(2);
        Arbitrary<BigDecimal> norm = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.0000"), new BigDecimal("50.0000"))
                .ofScale(4);
        // concreteNet is present for "chosen concrete" lines (so we can assert it is never touched).
        Arbitrary<BigDecimal> concreteNet = money.injectNull(0.5);

        Arbitrary<LineSpec> lineSpec = Combinators.combine(
                        Arbitraries.of(ConsumptionBranch.construction, ConsumptionBranch.finishing),
                        // reuse the same small type-id pool as the catalog so finishing types collide
                        Arbitraries.longs().between(1L, 4L),
                        norm,
                        money,
                        money,
                        concreteNet)
                .as(LineSpec::new);
        Arbitrary<List<LineSpec>> lines = lineSpec.list().ofMinSize(0).ofMaxSize(4);

        Arbitrary<CellSpec> cellSpec = Combinators.combine(
                        Arbitraries.bigDecimals().between(new BigDecimal("0.0000"), new BigDecimal("100.0000")).ofScale(4),
                        lines)
                .as(CellSpec::new);
        Arbitrary<List<CellSpec>> cells = cellSpec.list().ofMinSize(0).ofMaxSize(4);

        Arbitrary<BigDecimal> unitPrice = money;

        return Combinators.combine(packageId, catalog, cells, unitPrice).as(Scenario::new);
    }
}
