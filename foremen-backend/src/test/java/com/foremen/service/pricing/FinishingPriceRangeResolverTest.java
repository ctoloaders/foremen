package com.foremen.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.service.pricing.FinishingPriceRangeResolver.Key;
import com.foremen.service.pricing.FinishingPriceRangeResolver.PriceRange;

/**
 * Example (by-example) unit tests for {@link FinishingPriceRangeResolver}.
 *
 * <p>These pin the concrete behaviours the property test asserts in the general case
 * (FOR-05-05, Requirements 12.5, 3.4): the package-less fold across all packages (the assignment
 * default when there is no package context), the package-scoped restriction to member materials,
 * exclusion of materials with a {@code null} {@code retailNet}, exclusion of inactive materials,
 * and the empty bucket yielding {@link PriceRange#EMPTY} with no fabricated fallback.
 *
 * <p>Validates: Requirements 12.5, 3.4
 */
class FinishingPriceRangeResolverTest {

    private final FinishingPriceRangeResolver resolver = new FinishingPriceRangeResolver();

    @Test
    void packageLess_foldsMinAndMaxAcrossAllPackages() {
        MaterialTypeEntity type = type(1L);
        OfferPackageEntity pkgA = pkg(10L);
        OfferPackageEntity pkgB = pkg(20L);
        // Materials scattered across different packages (and one with none) all contribute to the
        // package-less fold — the widest honest band used as the assignment default (R3.4).
        List<FinishingMaterialEntity> materials = List.of(
                material(type, "10.00", true, pkgA),
                material(type, "25.50", true, pkgB),
                material(type, "17.30", true));

        PriceRange range = resolver.rangeFor(materials, 1L, null);

        assertThat(range.min()).isEqualByComparingTo("10.00");
        assertThat(range.max()).isEqualByComparingTo("25.50");
    }

    @Test
    void packageScoped_restrictsToMemberMaterialsOfThatPackage() {
        MaterialTypeEntity type = type(1L);
        OfferPackageEntity pkgA = pkg(10L);
        OfferPackageEntity pkgB = pkg(20L);
        List<FinishingMaterialEntity> materials = List.of(
                material(type, "10.00", true, pkgA),
                material(type, "99.00", true, pkgB),
                material(type, "40.00", true, pkgA),
                material(type, "1.00", true));

        // Scoped to package 10: only the two pkgA members (10.00, 40.00) contribute.
        PriceRange scoped = resolver.rangeFor(materials, 1L, 10L);
        assertThat(scoped.min()).isEqualByComparingTo("10.00");
        assertThat(scoped.max()).isEqualByComparingTo("40.00");

        // The package-less fold sees all four (min 1.00, max 99.00) — confirming the scope matters.
        PriceRange packageLess = resolver.rangeFor(materials, 1L, null);
        assertThat(packageLess.min()).isEqualByComparingTo("1.00");
        assertThat(packageLess.max()).isEqualByComparingTo("99.00");
    }

    @Test
    void packageScoped_multipleTypes_computeIndependentRangePerType() {
        MaterialTypeEntity typeA = type(1L);
        MaterialTypeEntity typeB = type(2L);
        OfferPackageEntity pkg = pkg(10L);
        List<FinishingMaterialEntity> materials = List.of(
                material(typeA, "10.00", true, pkg),
                material(typeA, "30.00", true, pkg),
                material(typeB, "5.00", true, pkg),
                material(typeB, "8.00", true, pkg));

        var ranges = resolver.compute(materials, 10L);

        assertThat(ranges).hasSize(2);
        assertThat(ranges.get(new Key(1L, 10L)).min()).isEqualByComparingTo("10.00");
        assertThat(ranges.get(new Key(1L, 10L)).max()).isEqualByComparingTo("30.00");
        assertThat(ranges.get(new Key(2L, 10L)).min()).isEqualByComparingTo("5.00");
        assertThat(ranges.get(new Key(2L, 10L)).max()).isEqualByComparingTo("8.00");
    }

    @Test
    void nullRetailNet_isExcludedFromTheRange() {
        MaterialTypeEntity type = type(1L);
        List<FinishingMaterialEntity> materials = List.of(
                material(type, "12.00", true),
                material(type, null, true),
                material(type, "40.00", true));

        PriceRange range = resolver.rangeFor(materials, 1L, null);

        // The null-retailNet material never widens the range.
        assertThat(range.min()).isEqualByComparingTo("12.00");
        assertThat(range.max()).isEqualByComparingTo("40.00");
    }

    @Test
    void inactiveMaterials_areExcludedFromTheRange() {
        MaterialTypeEntity type = type(1L);
        List<FinishingMaterialEntity> materials = List.of(
                material(type, "20.00", true),
                material(type, "1.00", false),
                material(type, "99.00", false),
                material(type, "35.00", true));

        PriceRange range = resolver.rangeFor(materials, 1L, null);

        // The inactive 1.00 / 99.00 materials do not contribute; only 20.00 and 35.00 count.
        assertThat(range.min()).isEqualByComparingTo("20.00");
        assertThat(range.max()).isEqualByComparingTo("35.00");
    }

    @Test
    void emptyBucket_yieldsEmptyRangeWithNoFabricatedFallback() {
        // No qualifying material for type 2 (only type 1 has data).
        MaterialTypeEntity type1 = type(1L);
        List<FinishingMaterialEntity> materials = List.of(
                material(type1, "10.00", true));

        PriceRange range = resolver.rangeFor(materials, 2L, null);

        assertThat(range.min()).isNull();
        assertThat(range.max()).isNull();
        assertThat(range).isEqualTo(PriceRange.EMPTY);
    }

    @Test
    void packageScoped_withNoMemberMaterials_yieldsEmptyRange() {
        MaterialTypeEntity type = type(1L);
        OfferPackageEntity pkgA = pkg(10L);
        List<FinishingMaterialEntity> materials = List.of(
                material(type, "10.00", true, pkgA));

        // Scoping to a package the material does not belong to leaves the bucket empty.
        PriceRange range = resolver.rangeFor(materials, 1L, 999L);

        assertThat(range).isEqualTo(PriceRange.EMPTY);
    }

    @Test
    void emptyInput_yieldsEmptyRangeForAnyType() {
        assertThat(resolver.compute(List.of(), null)).isEmpty();
        assertThat(resolver.rangeFor(List.of(), 1L, null)).isEqualTo(PriceRange.EMPTY);
    }

    private static MaterialTypeEntity type(Long id) {
        MaterialTypeEntity type = new MaterialTypeEntity();
        type.setId(id);
        type.setCode("TYPE-" + id);
        type.setNameRU("Тип " + id);
        type.setNamePL("Typ " + id);
        return type;
    }

    private static OfferPackageEntity pkg(Long id) {
        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setId(id);
        pkg.setCode("PKG-" + id);
        pkg.setOrderNo(id.intValue());
        pkg.setNameRU("Пакет " + id);
        pkg.setNamePL("Pakiet " + id);
        return pkg;
    }

    private static FinishingMaterialEntity material(
            MaterialTypeEntity type, String retailNet, boolean active, OfferPackageEntity... packages) {
        FinishingMaterialEntity material = new FinishingMaterialEntity();
        material.setType(type);
        material.setRetailNet(retailNet == null ? null : new BigDecimal(retailNet));
        material.setActive(active);
        material.setPackages(Set.of(packages));
        return material;
    }
}
