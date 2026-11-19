package com.foremen.service.estimate;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Unit and property tests for {@link EstimatePackagePricing} (FOR-05-04 additional tasks, task 2):
 * total = package price per m2 x apartment area + extra works beyond the package.
 */
@Tag("Feature: FOR-05-04-additional-tasks, package pricing")
class EstimatePackagePricingTest {

    @Test
    @DisplayName("total = zlM2 x area + extras (worked example)")
    void workedExample() {
        EstimatePackagePricing.Result r = EstimatePackagePricing.compute(
                new BigDecimal("1200.00"), new BigDecimal("54.30"),
                List.of(new BigDecimal("300.50"), new BigDecimal("99.50")));

        assertThat(r.packageSubtotal()).isEqualByComparingTo("65160.00");
        assertThat(r.extrasSubtotal()).isEqualByComparingTo("400.00");
        assertThat(r.total()).isEqualByComparingTo("65560.00");
    }

    @Test
    @DisplayName("no extras: total equals the package subtotal")
    void noExtras() {
        EstimatePackagePricing.Result r = EstimatePackagePricing.compute(
                new BigDecimal("1000"), new BigDecimal("50"), List.of());

        assertThat(r.extrasSubtotal()).isEqualByComparingTo("0");
        assertThat(r.total()).isEqualByComparingTo("50000.00");
    }

    @Test
    @DisplayName("null / negative inputs contribute zero")
    void nullAndNegativeInputsAreZero() {
        EstimatePackagePricing.Result r = EstimatePackagePricing.compute(
                null, new BigDecimal("-5"), Arrays.asList(null, new BigDecimal("-1"), new BigDecimal("10")));

        assertThat(r.packageSubtotal()).isEqualByComparingTo("0");
        assertThat(r.extrasSubtotal()).isEqualByComparingTo("10.00");
        assertThat(r.total()).isEqualByComparingTo("10.00");
        assertThat(EstimatePackagePricing.compute(null, null, null).total()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("package subtotal is rounded HALF_UP to 2 decimals")
    void roundsHalfUp() {
        EstimatePackagePricing.Result r = EstimatePackagePricing.compute(
                new BigDecimal("10.01"), new BigDecimal("0.55"), List.of());

        assertThat(r.packageSubtotal()).isEqualByComparingTo("5.51");
    }

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-additional-tasks, Property: total decomposition")
    void totalIsPackageSubtotalPlusExtras(
            @ForAll("prices") BigDecimal zlM2,
            @ForAll("areas") BigDecimal area,
            @ForAll("extras") List<BigDecimal> extras) {
        EstimatePackagePricing.Result r = EstimatePackagePricing.compute(zlM2, area, extras);

        BigDecimal expectedPackage = zlM2.multiply(area).setScale(2, RoundingMode.HALF_UP);
        BigDecimal expectedExtras = extras.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        assertThat(r.packageSubtotal()).isEqualByComparingTo(expectedPackage);
        assertThat(r.extrasSubtotal()).isEqualByComparingTo(expectedExtras);
        assertThat(r.total()).isEqualByComparingTo(expectedPackage.add(expectedExtras));
        assertThat(r.total().signum()).isGreaterThanOrEqualTo(0);
    }

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-additional-tasks, Property: extra works only add")
    void addingAnExtraWorkNeverDecreasesTotal(
            @ForAll("prices") BigDecimal zlM2,
            @ForAll("areas") BigDecimal area,
            @ForAll("extras") List<BigDecimal> extras,
            @ForAll("prices") BigDecimal more) {
        List<BigDecimal> bigger = new ArrayList<>(extras);
        bigger.add(more);

        assertThat(EstimatePackagePricing.compute(zlM2, area, bigger).total())
                .isGreaterThanOrEqualTo(EstimatePackagePricing.compute(zlM2, area, extras).total());
    }

    @Provide
    Arbitrary<BigDecimal> prices() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("5000")).ofScale(2);
    }

    @Provide
    Arbitrary<BigDecimal> areas() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("500")).ofScale(2);
    }

    @Provide
    Arbitrary<List<BigDecimal>> extras() {
        return prices().list().ofMaxSize(8);
    }
}
