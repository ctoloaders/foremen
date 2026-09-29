package com.foremen.service.pricing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.service.pricing.FinishingPriceRangeResolver.PriceRange;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based test for {@link FinishingPriceRangeResolver} (FOR-05-05, task 3.2).
 *
 * <p>The finishing-material price range for a type {@code T} (optionally scoped to a package
 * {@code P}) is the {@code MIN..MAX} of {@code retailNet} across exactly the finishing materials
 * that are active, have a non-null {@code retailNet}, have {@code type == T}, and — when {@code P}
 * is given — carry {@code P} in their {@code finishing_material_packages} membership. It is
 * {@link PriceRange#EMPTY} ({@code null..null}) when no such material exists (no fabricated
 * fallback), and it is unaffected by inactive, {@code null}-{@code retailNet}, other-type, or
 * non-member materials.
 *
 * <p>Feature: for-05-05-bill-of-materials, Property 8: Finishing type[/package] price range is
 * MIN..MAX over qualifying materials
 *
 * <p><b>Validates: Requirements 12.5</b>
 */
// Feature: for-05-05-bill-of-materials, Property 8: Finishing type[/package] price range is MIN..MAX over qualifying materials
@Tag("Feature: for-05-05-bill-of-materials, Property 8: Finishing type[/package] price range is MIN..MAX over qualifying materials")
class FinishingPriceRangeResolverPropertyTest {

    private final FinishingPriceRangeResolver resolver = new FinishingPriceRangeResolver();

    /**
     * Property 8: for every generated (type, package) pair the resolved range equals MIN..MAX of the
     * qualifying {@code retailNet} — active, non-null {@code retailNet}, matching type, and (when a
     * package scope is given) member of that package — and {@link PriceRange#EMPTY} when none
     * qualify. Inactive, unpriced, other-type, and non-member materials never contribute.
     *
     * <p>Feature: for-05-05-bill-of-materials, Property 8: Finishing type[/package] price range is
     * MIN..MAX over qualifying materials
     *
     * <p><b>Validates: Requirements 12.5</b>
     */
    @Property(tries = 100)
    @Tag("Feature: for-05-05-bill-of-materials, Property 8: Finishing type[/package] price range is MIN..MAX over qualifying materials")
    void rangeIsMinMaxOverQualifyingMaterials(@ForAll("materialSpecs") List<MaterialSpec> specs) {

        List<FinishingMaterialEntity> materials = new ArrayList<>();
        for (MaterialSpec spec : specs) {
            materials.add(spec.toEntity());
        }

        // The package scopes to probe: the package-less fold (null) plus every generated package id.
        Set<Long> packageScopes = new HashSet<>();
        packageScopes.add(null);
        for (MaterialSpec spec : specs) {
            packageScopes.addAll(spec.packageIds);
        }
        // A package that no material belongs to, to exercise the "no member" -> EMPTY branch.
        Long absentPackageId = 888_888L;
        packageScopes.add(absentPackageId);

        Set<Long> typeIds = new HashSet<>();
        for (MaterialSpec spec : specs) {
            typeIds.add(spec.typeId);
        }
        // A type that appears in no material, to exercise the "no such type" -> EMPTY branch.
        Long absentTypeId = 999_999L;
        typeIds.add(absentTypeId);

        for (Long pkg : packageScopes) {
            // Independently recompute the expected MIN..MAX per type over qualifying materials:
            // active AND non-null retailNet AND (pkg == null OR the material is a member of pkg).
            Map<Long, BigDecimal> expectedMin = new HashMap<>();
            Map<Long, BigDecimal> expectedMax = new HashMap<>();
            for (MaterialSpec spec : specs) {
                if (!spec.active || spec.retailNet == null) {
                    continue;
                }
                if (pkg != null && !spec.packageIds.contains(pkg)) {
                    continue;
                }
                expectedMin.merge(spec.typeId, spec.retailNet, BigDecimal::min);
                expectedMax.merge(spec.typeId, spec.retailNet, BigDecimal::max);
            }

            for (Long typeId : typeIds) {
                PriceRange actual = resolver.rangeFor(materials, typeId, pkg);
                if (expectedMin.containsKey(typeId)) {
                    assertThat(actual.min()).isEqualByComparingTo(expectedMin.get(typeId));
                    assertThat(actual.max()).isEqualByComparingTo(expectedMax.get(typeId));
                } else {
                    // No qualifying material -> EMPTY, no fabricated fallback (R12.5).
                    assertThat(actual.min()).isNull();
                    assertThat(actual.max()).isNull();
                }
            }
        }

        // A package with no member material is always EMPTY for every type (member restriction).
        for (Long typeId : typeIds) {
            PriceRange absent = resolver.rangeFor(materials, typeId, absentPackageId);
            assertThat(absent.min()).isNull();
            assertThat(absent.max()).isNull();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /**
     * A flat spec for a finishing material varying the four axes that matter to the property:
     * {@code type}, {@code retailNet} (incl. {@code null}), {@code active}, and package membership.
     */
    private record MaterialSpec(Long typeId, BigDecimal retailNet, boolean active, Set<Long> packageIds) {

        FinishingMaterialEntity toEntity() {
            MaterialTypeEntity type = new MaterialTypeEntity();
            type.setId(typeId);
            type.setCode("TYPE-" + typeId);
            type.setNameRU("type-ru-" + typeId);
            type.setNamePL("type-pl-" + typeId);

            Set<OfferPackageEntity> packages = new HashSet<>();
            for (Long pkgId : packageIds) {
                OfferPackageEntity pkg = new OfferPackageEntity();
                pkg.setId(pkgId);
                pkg.setCode("PKG-" + pkgId);
                pkg.setOrderNo(pkgId.intValue());
                pkg.setNameRU("pkg-ru-" + pkgId);
                pkg.setNamePL("pkg-pl-" + pkgId);
                packages.add(pkg);
            }

            FinishingMaterialEntity material = new FinishingMaterialEntity();
            material.setType(type);
            material.setRetailNet(retailNet);
            material.setActive(active);
            material.setPackages(packages);
            return material;
        }
    }

    @Provide
    Arbitrary<List<MaterialSpec>> materialSpecs() {
        // Small type pool so multiple materials collide on the same type, giving MIN..MAX a fold.
        Arbitrary<Long> typeIds = Arbitraries.longs().between(1L, 5L);
        // retailNet includes null (unpriced) and non-null values; scale 2 mirrors the column.
        Arbitrary<BigDecimal> retailNet = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.00"), new BigDecimal("100000.00"))
                .ofScale(2)
                .injectNull(0.25);
        Arbitrary<Boolean> active = Arbitraries.of(true, false);
        // Small package-id pool so materials collide on packages; 0..3 members per material,
        // exercising package-less-only materials as well as multi-package membership.
        Arbitrary<Set<Long>> packageIds = Arbitraries.longs()
                .between(1L, 4L)
                .set()
                .ofMinSize(0)
                .ofMaxSize(3);

        Arbitrary<MaterialSpec> spec = Combinators.combine(typeIds, retailNet, active, packageIds)
                .as(MaterialSpec::new);
        return spec.list().ofMinSize(0).ofMaxSize(30);
    }
}
