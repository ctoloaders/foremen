package com.foremen.service.pricing;

import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.WorkPackagePriceEntity;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link EffectivePriceResolver} — the pure, total, deterministic
 * max-fallback effective-price function (FOR-04-12b, Requirement 3).
 *
 * <p>The resolver is exercised directly as a pure function of
 * {@code (packagePrices collection, requested package code)} — no persistence — so the four
 * Correctness Properties are cheap to run over 100+ iterations each.
 *
 * <p>Generators produce {@link WorkPackagePriceEntity} collections of varying size (including empty
 * and singleton), each member carrying an {@code offerPackage.code} drawn from the seeded package
 * set plus some absent codes, and a positive {@code netPrice}. The requested package code is also
 * drawn from the seeded-plus-absent code pool so both the exact-match and max-fallback branches are
 * exercised.
 *
 * <p>Feature: FOR-04-12b-work-prices-packages, Property 1
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7</b>
 */
@Tag("Feature: FOR-04-12b-work-prices-packages, Property 1: Resolver case-correctness (exact / max-fallback / unpriced)")
class EffectivePriceResolverPropertyTest {

    /** Seeded package codes (FOR-04-10) plus a couple of codes that never appear as members. */
    private static final List<String> SEEDED_CODES = List.of("budget", "norm", "lux");
    private static final List<String> REQUESTABLE_CODES =
            List.of("budget", "norm", "lux", "premium", "custom");

    private final EffectivePriceResolver resolver = new EffectivePriceResolver();

    // ------------------------------------------------------------------------------------------
    // Property 1: Resolver case-correctness (exact / max-fallback / unpriced)
    // Validates: Requirements 3.1, 3.2, 3.3, 3.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-04-12b-work-prices-packages, Property 1: Resolver case-correctness (exact / max-fallback / unpriced)")
    void caseCorrectness(@ForAll("packagePriceCollections") List<WorkPackagePriceEntity> prices,
                         @ForAll("requestedCodes") String packageCode) {
        Optional<BigDecimal> actual = resolver.resolve(prices, packageCode);

        Optional<BigDecimal> exact = prices.stream()
                .filter(p -> packageCode.equals(p.getOfferPackage().getCode()))
                .map(WorkPackagePriceEntity::getNetPrice)
                .findFirst();

        if (exact.isPresent()) {
            // Case 1 (R3.2): an exact member for the requested code → that member's netPrice.
            assertThat(actual).isPresent();
            assertThat(actual.get()).isEqualByComparingTo(exact.get());
        } else if (!prices.isEmpty()) {
            // Case 2 (R3.3): no exact member but non-empty → the max netPrice in the collection.
            BigDecimal expectedMax = prices.stream()
                    .map(WorkPackagePriceEntity::getNetPrice)
                    .max(BigDecimal::compareTo)
                    .orElseThrow();
            assertThat(actual).isPresent();
            assertThat(actual.get()).isEqualByComparingTo(expectedMax);
        } else {
            // Case 3 (R3.4): empty collection → unpriced (no value).
            assertThat(actual).isEmpty();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 2: Single-member collection is uniform across packages
    // Validates: Requirement 3.6
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-04-12b-work-prices-packages, Property 2")
    void singleMemberIsUniformAcrossPackages(@ForAll("singleMemberCollections") List<WorkPackagePriceEntity> prices,
                                             @ForAll("requestedCodes") String packageCode) {
        BigDecimal onlyPrice = prices.get(0).getNetPrice();

        Optional<BigDecimal> actual = resolver.resolve(prices, packageCode);

        // R3.6: a single-member collection returns that one price for every requested package.
        assertThat(actual).isPresent();
        assertThat(actual.get()).isEqualByComparingTo(onlyPrice);
    }

    // ------------------------------------------------------------------------------------------
    // Property 3: Effective price never exceeds the collection maximum
    // Validates: Requirement 3.7
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-04-12b-work-prices-packages, Property 3")
    void neverExceedsCollectionMaximum(@ForAll("nonEmptyCollections") List<WorkPackagePriceEntity> prices,
                                       @ForAll("requestedCodes") String packageCode) {
        BigDecimal max = prices.stream()
                .map(WorkPackagePriceEntity::getNetPrice)
                .max(BigDecimal::compareTo)
                .orElseThrow();

        Optional<BigDecimal> actual = resolver.resolve(prices, packageCode);

        // R3.7: over a non-empty collection, the resolved price is <= max(netPrice).
        assertThat(actual).isPresent();
        assertThat(actual.get()).isLessThanOrEqualTo(max);
    }

    // ------------------------------------------------------------------------------------------
    // Property 4: Resolution is deterministic
    // Validates: Requirement 3.5
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-04-12b-work-prices-packages, Property 4")
    void resolutionIsDeterministic(@ForAll("packagePriceCollections") List<WorkPackagePriceEntity> prices,
                                   @ForAll("requestedCodes") String packageCode) {
        Optional<BigDecimal> first = resolver.resolve(prices, packageCode);
        Optional<BigDecimal> second = resolver.resolve(prices, packageCode);

        // R3.5: resolving twice over identical inputs yields identical results.
        assertThat(first.isPresent()).isEqualTo(second.isPresent());
        if (first.isPresent()) {
            assertThat(first.get()).isEqualByComparingTo(second.get());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /** A collection of 0..8 package prices, members carrying seeded codes and positive netPrices. */
    @Provide
    Arbitrary<List<WorkPackagePriceEntity>> packagePriceCollections() {
        return workPackagePrice().list().ofMinSize(0).ofMaxSize(8);
    }

    /** A non-empty collection (1..8 members) for the max-bound property. */
    @Provide
    Arbitrary<List<WorkPackagePriceEntity>> nonEmptyCollections() {
        return workPackagePrice().list().ofMinSize(1).ofMaxSize(8);
    }

    /** A collection holding exactly one member. */
    @Provide
    Arbitrary<List<WorkPackagePriceEntity>> singleMemberCollections() {
        return workPackagePrice().map(price -> {
            List<WorkPackagePriceEntity> single = new ArrayList<>(1);
            single.add(price);
            return single;
        });
    }

    /** Requested package code drawn from seeded codes plus codes that never appear as members. */
    @Provide
    Arbitrary<String> requestedCodes() {
        return Arbitraries.of(REQUESTABLE_CODES);
    }

    /** A single {@link WorkPackagePriceEntity} with a seeded package code and a positive netPrice. */
    private Arbitrary<WorkPackagePriceEntity> workPackagePrice() {
        Arbitrary<String> codes = Arbitraries.of(SEEDED_CODES);
        // Positive prices with 2-decimal scale over a wide range so max/exact branches differ.
        Arbitrary<BigDecimal> netPrices = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("100000.00"))
                .ofScale(2);
        return Combinators.combine(codes, netPrices).as(this::buildPrice);
    }

    private WorkPackagePriceEntity buildPrice(String code, BigDecimal netPrice) {
        OfferPackageEntity offerPackage = new OfferPackageEntity();
        offerPackage.setCode(code);

        WorkPackagePriceEntity price = new WorkPackagePriceEntity();
        price.setOfferPackage(offerPackage);
        price.setNetPrice(netPrice);
        return price;
    }
}
