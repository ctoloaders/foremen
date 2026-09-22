package com.foremen.service.pricing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.ConstructionMaterialEntity;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.service.pricing.PriceRangeResolver.PriceRange;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based test for the re-keyed {@link PriceRangeResolver} (FOR-05-04-UI, task 2.2).
 *
 * <p>After the material-side package collapse (Requirement 5.3, 5.7) the construction-material price
 * range is keyed by construction-material <b>type</b> only: the range for a type {@code T} is the
 * {@code MIN..MAX} of {@code retailNet} across exactly the materials that are active, have a
 * non-null {@code retailNet}, and have {@code type == T}; it is {@code null..null} when no such
 * material exists (no fabricated fallback); and it does not depend on, and is not keyed by, any
 * offer-package assignment.
 *
 * <p>Because the collapsed {@link ConstructionMaterialEntity} carries no package field at all, the
 * resolver cannot read a package binding — package-independence is structural. This test makes that
 * explicit by generating an irrelevant-by-design package assignment alongside each material and
 * asserting the computed range is invariant under any change to that assignment (it is never fed to
 * the resolver, so the result is identical by construction).
 *
 * <p>Feature: FOR-05-04-UI-estimate-packages-changes, Property 1: Construction-material price range
 * is type-keyed, package-independent, unfabricated
 *
 * <p><b>Validates: Requirements 5.3, 5.7</b>
 */
// Feature: FOR-05-04-UI-estimate-packages-changes, Property 1: Construction-material price range is type-keyed, package-independent, unfabricated
@Tag("Feature: FOR-05-04-UI-estimate-packages-changes, Property 1: Construction-material price range is type-keyed, package-independent, unfabricated")
class PriceRangeResolverPropertyTest {

    private final PriceRangeResolver resolver = new PriceRangeResolver();

    /**
     * Property 1: for every generated type the resolved range equals MIN..MAX of the qualifying
     * {@code retailNet} (active, non-null {@code retailNet}) and {@code null..null} when none
     * qualify — and it never depends on any offer-package assignment.
     *
     * <p>Feature: FOR-05-04-UI-estimate-packages-changes, Property 1: Construction-material price
     * range is type-keyed, package-independent, unfabricated
     *
     * <p><b>Validates: Requirements 5.3, 5.7</b>
     */
    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-UI-estimate-packages-changes, Property 1: Construction-material price range is type-keyed, package-independent, unfabricated")
    void rangeIsTypeKeyedPackageIndependentAndUnfabricated(
            @ForAll("materialSpecs") List<MaterialSpec> specs) {

        List<ConstructionMaterialEntity> materials = new ArrayList<>();
        for (MaterialSpec spec : specs) {
            materials.add(spec.toEntity());
        }

        // Independently recompute the expected MIN..MAX per type from the qualifying materials
        // (active AND non-null retailNet), ignoring package assignment entirely.
        Map<Long, BigDecimal> expectedMin = new HashMap<>();
        Map<Long, BigDecimal> expectedMax = new HashMap<>();
        for (MaterialSpec spec : specs) {
            if (!spec.active || spec.retailNet == null) {
                continue;
            }
            expectedMin.merge(spec.typeId, spec.retailNet, BigDecimal::min);
            expectedMax.merge(spec.typeId, spec.retailNet, BigDecimal::max);
        }

        // Every generated type id must resolve to the expected range (R5.3: type-keyed).
        for (MaterialSpec spec : specs) {
            PriceRange actual = resolver.rangeFor(materials, spec.typeId);
            if (expectedMin.containsKey(spec.typeId)) {
                assertThat(actual.min()).isEqualByComparingTo(expectedMin.get(spec.typeId));
                assertThat(actual.max()).isEqualByComparingTo(expectedMax.get(spec.typeId));
            } else {
                // R5.7: no qualifying material -> null..null, no fabricated fallback.
                assertThat(actual.min()).isNull();
                assertThat(actual.max()).isNull();
            }
        }

        // R5.7: a type that appears in NO material at all is an empty range, never fabricated.
        Long absentTypeId = 999_999L;
        PriceRange absent = resolver.rangeFor(materials, absentTypeId);
        assertThat(absent.min()).isNull();
        assertThat(absent.max()).isNull();

        // R5.3/R5.7: package-independence. The resolver reads only type/retailNet/active; the
        // generated package assignment is never wired into the entity, so mutating each spec's
        // package assignment and recomputing yields the identical result. Rebuild the material list
        // with every package assignment flipped and assert the resolved ranges are unchanged.
        List<ConstructionMaterialEntity> repackaged = new ArrayList<>();
        for (MaterialSpec spec : specs) {
            repackaged.add(spec.withFlippedPackage().toEntity());
        }
        for (MaterialSpec spec : specs) {
            PriceRange original = resolver.rangeFor(materials, spec.typeId);
            PriceRange afterRepackage = resolver.rangeFor(repackaged, spec.typeId);
            assertThat(rangeEquals(original, afterRepackage))
                    .as("range for type %d must be invariant under package reassignment", spec.typeId)
                    .isTrue();
        }
    }

    private static boolean rangeEquals(PriceRange a, PriceRange b) {
        return nullSafeEquals(a.min(), b.min()) && nullSafeEquals(a.max(), b.max());
    }

    private static boolean nullSafeEquals(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /**
     * A flat spec for a construction material varying the four axes that matter to the property:
     * {@code type}, {@code retailNet} (incl. {@code null}), {@code active}, and an
     * irrelevant-by-design {@code offerPackageId} (never fed to the resolver).
     */
    private record MaterialSpec(Long typeId, BigDecimal retailNet, boolean active, Long offerPackageId) {

        MaterialSpec withFlippedPackage() {
            // Any change to the package assignment; the value is irrelevant to the resolver.
            return new MaterialSpec(typeId, retailNet, active, offerPackageId + 1);
        }

        ConstructionMaterialEntity toEntity() {
            ConstructionMaterialTypeEntity type = new ConstructionMaterialTypeEntity();
            type.setId(typeId);
            type.setCode("TYPE-" + typeId);
            type.setNameRU("type-ru-" + typeId);
            type.setNamePL("type-pl-" + typeId);

            ConstructionMaterialEntity material = new ConstructionMaterialEntity();
            material.setType(type);
            material.setNameRU("mat-ru");
            material.setNamePL("mat-pl");
            material.setRetailNet(retailNet);
            material.setActive(active);
            return material;
        }
    }

    @Provide
    Arbitrary<List<MaterialSpec>> materialSpecs() {
        // Constrain type ids to a small pool so multiple materials collide on the same type, giving
        // the MIN..MAX fold something to fold over.
        Arbitrary<Long> typeIds = Arbitraries.longs().between(1L, 5L);
        // retailNet includes null (unpriced) and non-null values; scale 2 mirrors the column.
        Arbitrary<BigDecimal> retailNet = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.00"), new BigDecimal("100000.00"))
                .ofScale(2)
                .injectNull(0.25);
        Arbitrary<Boolean> active = Arbitraries.of(true, false);
        Arbitrary<Long> offerPackageIds = Arbitraries.longs().between(1L, 10L);

        Arbitrary<MaterialSpec> spec = Combinators.combine(typeIds, retailNet, active, offerPackageIds)
                .as(MaterialSpec::new);
        return spec.list().ofMinSize(0).ofMaxSize(30);
    }
}
