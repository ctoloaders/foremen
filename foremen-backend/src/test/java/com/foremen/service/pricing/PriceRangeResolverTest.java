package com.foremen.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.service.pricing.PriceRangeResolver.PriceRange;
import com.foremen.service.pricing.PriceRangeResolver.PriceRangeKey;

/**
 * Example (by-example) unit tests for the re-keyed {@link PriceRangeResolver}.
 *
 * <p>After the FOR-05-04-UI material-side package collapse the price range (вилка цен) is keyed by
 * construction-material {@link ConstructionMaterialTypeEntity type} only (Requirement 5.7). These
 * tests pin the concrete behaviours the property test asserts in the general case: a single type, a
 * mix of types, exclusion of materials with a {@code null} {@code retailNet}, exclusion of inactive
 * materials, and the empty bucket yielding {@code null..null} with no fabricated fallback.
 *
 * <p>Validates: Requirements 5.7
 */
class PriceRangeResolverTest {

    private final PriceRangeResolver resolver = new PriceRangeResolver();

    @Test
    void singleType_foldsMinAndMaxAcrossQualifyingMaterials() {
        ConstructionMaterialTypeEntity type = type(1L);
        List<ConstructionMaterialEntity> materials = List.of(
                material(type, "10.00", true),
                material(type, "25.50", true),
                material(type, "17.30", true));

        PriceRange range = resolver.rangeFor(materials, 1L);

        assertThat(range.min()).isEqualByComparingTo("10.00");
        assertThat(range.max()).isEqualByComparingTo("25.50");
    }

    @Test
    void multipleTypes_computeIndependentRangePerType() {
        ConstructionMaterialTypeEntity typeA = type(1L);
        ConstructionMaterialTypeEntity typeB = type(2L);
        List<ConstructionMaterialEntity> materials = List.of(
                material(typeA, "10.00", true),
                material(typeA, "30.00", true),
                material(typeB, "5.00", true),
                material(typeB, "8.00", true));

        var ranges = resolver.compute(materials);

        assertThat(ranges).hasSize(2);
        assertThat(ranges.get(new PriceRangeKey(1L)).min()).isEqualByComparingTo("10.00");
        assertThat(ranges.get(new PriceRangeKey(1L)).max()).isEqualByComparingTo("30.00");
        assertThat(ranges.get(new PriceRangeKey(2L)).min()).isEqualByComparingTo("5.00");
        assertThat(ranges.get(new PriceRangeKey(2L)).max()).isEqualByComparingTo("8.00");
    }

    @Test
    void nullRetailNet_isExcludedFromTheRange() {
        ConstructionMaterialTypeEntity type = type(1L);
        List<ConstructionMaterialEntity> materials = List.of(
                material(type, "12.00", true),
                material(type, null, true),
                material(type, "40.00", true));

        PriceRange range = resolver.rangeFor(materials, 1L);

        // The null-retailNet material never widens the range.
        assertThat(range.min()).isEqualByComparingTo("12.00");
        assertThat(range.max()).isEqualByComparingTo("40.00");
    }

    @Test
    void inactiveMaterials_areExcludedFromTheRange() {
        ConstructionMaterialTypeEntity type = type(1L);
        List<ConstructionMaterialEntity> materials = List.of(
                material(type, "20.00", true),
                material(type, "1.00", false),
                material(type, "99.00", false),
                material(type, "35.00", true));

        PriceRange range = resolver.rangeFor(materials, 1L);

        // The inactive 1.00 / 99.00 materials do not contribute; only 20.00 and 35.00 count.
        assertThat(range.min()).isEqualByComparingTo("20.00");
        assertThat(range.max()).isEqualByComparingTo("35.00");
    }

    @Test
    void emptyBucket_yieldsNullNullWithNoFabricatedFallback() {
        // No qualifying material for type 2 (only type 1 has data).
        ConstructionMaterialTypeEntity type1 = type(1L);
        List<ConstructionMaterialEntity> materials = List.of(
                material(type1, "10.00", true));

        PriceRange range = resolver.rangeFor(materials, 2L);

        assertThat(range.min()).isNull();
        assertThat(range.max()).isNull();
        assertThat(range).isEqualTo(PriceRange.EMPTY);
    }

    @Test
    void emptyInput_yieldsEmptyRangeForAnyType() {
        assertThat(resolver.compute(List.of())).isEmpty();
        assertThat(resolver.rangeFor(List.of(), 1L)).isEqualTo(PriceRange.EMPTY);
    }

    private static ConstructionMaterialTypeEntity type(Long id) {
        ConstructionMaterialTypeEntity type = new ConstructionMaterialTypeEntity();
        type.setId(id);
        type.setCode("TYPE-" + id);
        type.setNameRU("Тип " + id);
        type.setNamePL("Typ " + id);
        return type;
    }

    private static ConstructionMaterialEntity material(
            ConstructionMaterialTypeEntity type, String retailNet, boolean active) {
        ConstructionMaterialEntity material = new ConstructionMaterialEntity();
        material.setType(type);
        material.setRetailNet(retailNet == null ? null : new BigDecimal(retailNet));
        material.setActive(active);
        return material;
    }
}
