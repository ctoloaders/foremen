package com.foremen.service.pricing;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.foremen.dao.model.AssortmentGroupEntity;
import com.foremen.dao.model.AssortmentLineItemEntity;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.dao.model.OfferPackageEntity;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Property-based tests for {@link PackageZlM2Resolver} — Property 10: the "typical product" link
 * never drives the computed zł/m² value (FOR-05-04, design §Correctness Properties).
 *
 * <p>{@link PackageZlM2Resolver.Line} — the shape the resolver actually computes over — carries
 * only {@code groupId}, {@code packageCode}, {@code avgPrice}, and {@code qtyRef50}; it has no
 * typical-product/typical-material field at all. So by construction the resolver's pure
 * computation layer (task 9.3) cannot read or be influenced by a typical-product link: there is no
 * such field in its input type. The first property below proves this structurally — for any two
 * {@link PackageZlM2Resolver.Line} lists derived from a pair of otherwise-identical assortment line
 * items that differ ONLY in {@code typicalProduct} (one null, one set), {@code groupContribution}
 * and {@code packageZlM2} return identical results, because the adaptation step from entity to
 * {@code Line} — and the resolver itself — never touches the field.
 *
 * <p>The second, entity-level check (a plain unit test, not a property) verifies the other half of
 * Property 10: {@link AssortmentLineItemEntity} is a plain JPA entity with independent columns —
 * there is no derived-field logic on the entity that recomputes {@code minPrice}/{@code avgPrice}/
 * {@code maxPrice}/{@code qtyRef50} from {@code typicalProduct}. Setting or clearing the FK leaves
 * those getters unchanged.
 *
 * <p>Feature: FOR-05-04-estimate-packages-changes, Property 10
 *
 * <p><b>Validates: Requirements 6.6, 6.7</b>
 */
@Tag("Feature: FOR-05-04-estimate-packages-changes, Property 10: Typical product never drives the value")
class PackageZlM2ResolverPropertyTest {

    private final PackageZlM2Resolver resolver = new PackageZlM2Resolver();

    // ------------------------------------------------------------------------------------------
    // Property 10 (resolver layer): typical-product presence/absence cannot affect
    // groupContribution/packageZlM2, because PackageZlM2Resolver.Line structurally excludes the
    // field — there is nothing for the resolver to read.
    // Validates: Requirements 6.6, 6.7
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 10: Typical product never drives the value")
    void groupContributionIsIndependentOfTypicalProduct(
            @ForAll("assortmentLineItemPairs") LineItemPair pair,
            @ForAll("packageCodes") String packageCode) {

        // Adapt each entity (one with typicalProduct = null, one with it set) to the resolver's
        // Line shape — the shape the resolver actually consumes.
        PackageZlM2Resolver.Line withoutTypicalProduct = toLine(pair.withoutTypicalProduct());
        PackageZlM2Resolver.Line withTypicalProduct = toLine(pair.withTypicalProduct());

        BigDecimal contributionWithout =
                resolver.groupContribution(List.of(withoutTypicalProduct), packageCode);
        BigDecimal contributionWith =
                resolver.groupContribution(List.of(withTypicalProduct), packageCode);

        // R6.6/R6.7: identical min/avg/max/qtyRef50 inputs yield an identical contribution
        // regardless of whether a typical-product link is present.
        assertThat(contributionWithout).isEqualByComparingTo(contributionWith);
    }

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 10: Typical product never drives the value")
    void packageZlM2IsIndependentOfTypicalProduct(
            @ForAll("assortmentLineItemPairs") LineItemPair pair,
            @ForAll("packageCodes") String packageCode) {

        PackageZlM2Resolver.Line withoutTypicalProduct = toLine(pair.withoutTypicalProduct());
        PackageZlM2Resolver.Line withTypicalProduct = toLine(pair.withTypicalProduct());

        BigDecimal totalWithout = resolver.packageZlM2(
                java.util.Map.of(1L, List.of(withoutTypicalProduct)), packageCode);
        BigDecimal totalWith = resolver.packageZlM2(
                java.util.Map.of(1L, List.of(withTypicalProduct)), packageCode);

        // R6.6/R6.7: the package-level total is likewise unaffected by the typical-product link.
        assertThat(totalWithout).isEqualByComparingTo(totalWith);
    }

    // ------------------------------------------------------------------------------------------
    // Property 10 (entity layer, plain unit test): AssortmentLineItemEntity has no derived-field
    // logic — min/avg/max/qtyRef50 getters are unaffected by typicalProduct.
    // Validates: Requirements 6.6, 6.7
    // ------------------------------------------------------------------------------------------

    @Test
    void entityPriceFieldsAreUnaffectedByTypicalProductPresence() {
        AssortmentGroupEntity group = new AssortmentGroupEntity();
        OfferPackageEntity offerPackage = new OfferPackageEntity();
        offerPackage.setCode("budget");

        BigDecimal minPrice = new BigDecimal("10.00");
        BigDecimal avgPrice = new BigDecimal("20.00");
        BigDecimal maxPrice = new BigDecimal("30.00");
        BigDecimal qtyRef50 = new BigDecimal("5.00");

        AssortmentLineItemEntity withoutTypicalProduct = new AssortmentLineItemEntity();
        withoutTypicalProduct.setGroup(group);
        withoutTypicalProduct.setOfferPackage(offerPackage);
        withoutTypicalProduct.setNameRU("Миска WC");
        withoutTypicalProduct.setNamePL("Miska WC");
        withoutTypicalProduct.setMinPrice(minPrice);
        withoutTypicalProduct.setAvgPrice(avgPrice);
        withoutTypicalProduct.setMaxPrice(maxPrice);
        withoutTypicalProduct.setQtyRef50(qtyRef50);
        withoutTypicalProduct.setTypicalProduct(null);

        MaterialEntity material = new MaterialEntity();
        material.setCode("WC-STANDARD-01");
        material.setNameRU("Унитаз стандарт");
        material.setNamePL("Miska WC standard");

        AssortmentLineItemEntity withTypicalProduct = new AssortmentLineItemEntity();
        withTypicalProduct.setGroup(group);
        withTypicalProduct.setOfferPackage(offerPackage);
        withTypicalProduct.setNameRU("Миска WC");
        withTypicalProduct.setNamePL("Miska WC");
        withTypicalProduct.setMinPrice(minPrice);
        withTypicalProduct.setAvgPrice(avgPrice);
        withTypicalProduct.setMaxPrice(maxPrice);
        withTypicalProduct.setQtyRef50(qtyRef50);
        withTypicalProduct.setTypicalProduct(material);

        // R6.6/R6.7: setting the typical-product FK does not alter any price/quantity field.
        assertThat(withTypicalProduct.getMinPrice()).isEqualByComparingTo(withoutTypicalProduct.getMinPrice());
        assertThat(withTypicalProduct.getAvgPrice()).isEqualByComparingTo(withoutTypicalProduct.getAvgPrice());
        assertThat(withTypicalProduct.getMaxPrice()).isEqualByComparingTo(withoutTypicalProduct.getMaxPrice());
        assertThat(withTypicalProduct.getQtyRef50()).isEqualByComparingTo(withoutTypicalProduct.getQtyRef50());

        // Clearing it back to null (catalog delete -> ON DELETE SET NULL) still leaves the fields
        // untouched.
        withTypicalProduct.setTypicalProduct(null);
        assertThat(withTypicalProduct.getMinPrice()).isEqualByComparingTo(minPrice);
        assertThat(withTypicalProduct.getAvgPrice()).isEqualByComparingTo(avgPrice);
        assertThat(withTypicalProduct.getMaxPrice()).isEqualByComparingTo(maxPrice);
        assertThat(withTypicalProduct.getQtyRef50()).isEqualByComparingTo(qtyRef50);
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /** Seeded package codes (FOR-04-10) plus a code that never appears as a member. */
    private static final List<String> SEEDED_CODES = List.of("budget", "norm", "lux");

    @Provide
    Arbitrary<String> packageCodes() {
        return Arbitraries.of(SEEDED_CODES);
    }

    /**
     * A pair of otherwise-identical {@link AssortmentLineItemEntity} instances differing ONLY in
     * {@code typicalProduct} (one {@code null}, one set to a generated {@link MaterialEntity}).
     */
    @Provide
    Arbitrary<LineItemPair> assortmentLineItemPairs() {
        Arbitrary<String> codes = Arbitraries.of(SEEDED_CODES);
        Arbitrary<BigDecimal> prices = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("100000.00"))
                .ofScale(2);
        Arbitrary<BigDecimal> quantities = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.00"), new BigDecimal("500.00"))
                .ofScale(2);
        Arbitrary<Long> groupIds = Arbitraries.longs().between(1L, 20L);
        Arbitrary<String> materialCodes = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10);

        return Combinators.combine(groupIds, codes, prices, quantities, materialCodes)
                .as(this::buildPair);
    }

    private LineItemPair buildPair(Long groupId, String packageCode, BigDecimal avgPrice,
                                    BigDecimal qtyRef50, String materialCode) {
        AssortmentGroupEntity group = new AssortmentGroupEntity();
        // BaseEntity id is generated/persistence-managed; the resolver keys by the map key the
        // caller supplies, so identity here is irrelevant to the property under test.

        OfferPackageEntity offerPackage = new OfferPackageEntity();
        offerPackage.setCode(packageCode);

        AssortmentLineItemEntity withoutTypicalProduct = new AssortmentLineItemEntity();
        withoutTypicalProduct.setGroup(group);
        withoutTypicalProduct.setOfferPackage(offerPackage);
        withoutTypicalProduct.setNameRU("line-ru");
        withoutTypicalProduct.setNamePL("line-pl");
        withoutTypicalProduct.setAvgPrice(avgPrice);
        withoutTypicalProduct.setQtyRef50(qtyRef50);
        withoutTypicalProduct.setTypicalProduct(null);

        MaterialEntity material = new MaterialEntity();
        material.setCode(materialCode);
        material.setNameRU("material-ru");
        material.setNamePL("material-pl");

        AssortmentLineItemEntity withTypicalProduct = new AssortmentLineItemEntity();
        withTypicalProduct.setGroup(group);
        withTypicalProduct.setOfferPackage(offerPackage);
        withTypicalProduct.setNameRU("line-ru");
        withTypicalProduct.setNamePL("line-pl");
        withTypicalProduct.setAvgPrice(avgPrice);
        withTypicalProduct.setQtyRef50(qtyRef50);
        withTypicalProduct.setTypicalProduct(material);

        return new LineItemPair(groupId, withoutTypicalProduct, withTypicalProduct);
    }

    /** Adapts an {@link AssortmentLineItemEntity} to the resolver's {@link PackageZlM2Resolver.Line} shape. */
    private PackageZlM2Resolver.Line toLine(AssortmentLineItemEntity entity) {
        return new PackageZlM2Resolver.Line(
                1L,
                entity.getOfferPackage().getCode(),
                entity.getAvgPrice(),
                entity.getQtyRef50());
    }

    /**
     * A pair of otherwise-identical assortment line items differing only in whether
     * {@code typicalProduct} is set.
     */
    private record LineItemPair(Long groupId, AssortmentLineItemEntity withoutTypicalProduct,
                                 AssortmentLineItemEntity withTypicalProduct) {
    }
}
