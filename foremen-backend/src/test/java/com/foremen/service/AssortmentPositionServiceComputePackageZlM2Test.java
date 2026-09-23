package com.foremen.service;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.foremen.dao.AssortmentGroupDao;
import com.foremen.dao.AssortmentPositionDao;
import com.foremen.dao.AssortmentPositionPriceDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.model.AssortmentGroupEntity;
import com.foremen.dao.model.AssortmentPositionEntity;
import com.foremen.dao.model.AssortmentPositionPriceEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.mapper.AssortmentPositionServiceMapper;
import com.foremen.service.pricing.PackageZlM2Resolver;

import jakarta.persistence.EntityManager;

/**
 * Unit test for {@link AssortmentPositionService#computePackageZlM2(String)} under the
 * FOR-05-04-UI per-price quantity model:
 *
 * <ul>
 *   <li>the HEADLINE package zł/m² is computed from the <b>MAX</b> price band (not avg);</li>
 *   <li>each position contributes {@code maxPrice × quantity}, where the quantity is the position
 *       price row's per-band override ({@code maxQty}) when set, else the owning group's
 *       {@code referenceQty} (the default);</li>
 *   <li>the package price is the sum across positions, divided by the 50 m² reference area.</li>
 * </ul>
 *
 * <p>Exercised with a mocked {@link AssortmentPositionPriceDao} (returning in-memory price
 * entities) and a real {@link PackageZlM2Resolver}; no Spring context, no database.
 */
class AssortmentPositionServiceComputePackageZlM2Test {

    private final AssortmentPositionDao dao = Mockito.mock(AssortmentPositionDao.class);
    private final AssortmentGroupDao groupDao = Mockito.mock(AssortmentGroupDao.class);
    private final AssortmentPositionPriceDao priceDao = Mockito.mock(AssortmentPositionPriceDao.class);
    private final OfferPackageDao offerPackageDao = Mockito.mock(OfferPackageDao.class);
    private final AssortmentPositionService service = new AssortmentPositionService(
            dao,
            groupDao,
            priceDao,
            offerPackageDao,
            Mockito.mock(AssortmentPositionServiceMapper.class),
            Mockito.mock(AuditLogDao.class),
            Mockito.mock(EntityManager.class),
            new PackageZlM2Resolver());

    @Test
    void computesHeadlineFromMaxPriceUsingGroupReferenceQty() {
        OfferPackageEntity budget = offerPackage("budget");
        OfferPackageEntity lux = offerPackage("lux");

        // Group A: referenceQty 3; budget max prices 10 + 20 => (10*3 + 20*3) / 50 = 90/50 = 1.80.
        AssortmentGroupEntity groupA = group(1L, new BigDecimal("3"));
        // Group B: referenceQty 2; budget max price 40 => (40*2) / 50 = 1.60.
        AssortmentGroupEntity groupB = group(2L, new BigDecimal("2"));

        List<AssortmentPositionPriceEntity> prices = List.of(
                price(groupA, budget, new BigDecimal("10")),
                price(groupA, budget, new BigDecimal("20")),
                // A lux price in group A must NOT affect the budget total.
                price(groupA, lux, new BigDecimal("999")),
                price(groupB, budget, new BigDecimal("40")));

        Mockito.when(priceDao.findAll()).thenReturn(prices);

        // 1.80 (group A) + 1.60 (group B) = 3.40
        assertThat(service.computePackageZlM2("budget")).isEqualByComparingTo(new BigDecimal("3.40"));
    }

    @Test
    void headlineUsesMaxNotAvgPrice() {
        OfferPackageEntity budget = offerPackage("budget");
        AssortmentGroupEntity groupA = group(1L, new BigDecimal("5"));

        // avg 100 but max 200; headline uses MAX => (200 * 5) / 50 = 20.00 (NOT 10.00 from avg).
        AssortmentPositionPriceEntity p = price(groupA, budget, new BigDecimal("200"));
        p.setAvgPrice(new BigDecimal("100"));
        Mockito.when(priceDao.findAll()).thenReturn(List.of(p));

        assertThat(service.computePackageZlM2("budget")).isEqualByComparingTo(new BigDecimal("20.00"));
    }

    @Test
    void perBandQuantityOverrideBeatsGroupReferenceQty() {
        OfferPackageEntity budget = offerPackage("budget");
        AssortmentGroupEntity groupA = group(1L, new BigDecimal("5"));

        // max 100, group refQty 5, but a maxQty override of 2 => (100 * 2) / 50 = 4.00
        // (NOT 10.00, which the group refQty would give).
        AssortmentPositionPriceEntity p = price(groupA, budget, new BigDecimal("100"));
        p.setMaxQty(new BigDecimal("2"));
        Mockito.when(priceDao.findAll()).thenReturn(List.of(p));

        assertThat(service.computePackageZlM2("budget")).isEqualByComparingTo(new BigDecimal("4.00"));
    }

    @Test
    void zeroQtyOverrideIsLegitimateAndContributesZero() {
        OfferPackageEntity budget = offerPackage("budget");
        AssortmentGroupEntity groupA = group(1L, new BigDecimal("5"));

        // max 100 with an explicit maxQty override of 0 => (100 * 0) / 50 = 0.00. A 0 override is a
        // real stored value (NOT treated as "no override"); only null falls back to the group qty.
        AssortmentPositionPriceEntity p = price(groupA, budget, new BigDecimal("100"));
        p.setMaxQty(BigDecimal.ZERO);
        Mockito.when(priceDao.findAll()).thenReturn(List.of(p));

        assertThat(service.computePackageZlM2("budget")).isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void missingOverrideFallsBackToGroupReferenceQty() {
        OfferPackageEntity budget = offerPackage("budget");
        AssortmentGroupEntity groupA = group(1L, new BigDecimal("4"));

        // No maxQty override => group refQty 4: (25 * 4) / 50 = 2.00.
        Mockito.when(priceDao.findAll())
                .thenReturn(List.of(price(groupA, budget, new BigDecimal("25"))));

        assertThat(service.computePackageZlM2("budget")).isEqualByComparingTo(new BigDecimal("2.00"));
    }

    @Test
    void nullMaxPriceContributesZero() {
        OfferPackageEntity budget = offerPackage("budget");
        AssortmentGroupEntity groupA = group(1L, new BigDecimal("4"));

        // One position with a null max price (contributes 0) + one with max 25 => (25 * 4) / 50 = 2.00.
        Mockito.when(priceDao.findAll()).thenReturn(List.of(
                price(groupA, budget, null),
                price(groupA, budget, new BigDecimal("25"))));

        assertThat(service.computePackageZlM2("budget")).isEqualByComparingTo(new BigDecimal("2.00"));
    }

    @Test
    void clearPositionQtyNullsTheBandOverrideInPlace() {
        OfferPackageEntity budget = offerPackage("budget");
        AssortmentGroupEntity groupA = group(1L, new BigDecimal("5"));

        AssortmentPositionEntity position = new AssortmentPositionEntity();
        position.setId(42L);
        position.setGroup(groupA);

        AssortmentPositionPriceEntity priceRow = new AssortmentPositionPriceEntity();
        priceRow.setPosition(position);
        priceRow.setOfferPackage(budget);
        priceRow.setMaxPrice(new BigDecimal("100"));
        priceRow.setMaxQty(new BigDecimal("2")); // an existing override to clear

        Mockito.when(offerPackageDao.findByCode("budget")).thenReturn(java.util.Optional.of(budget));
        Mockito.when(priceDao.findAll()).thenReturn(List.of(priceRow));

        service.clearPositionQty("budget", 42L, "max");

        // The band override is nulled in place (falls back to the group refQty afterwards).
        assertThat(priceRow.getMaxQty()).isNull();
        // Other bands untouched.
        assertThat(priceRow.getMinQty()).isNull();
        assertThat(priceRow.getAvgQty()).isNull();
    }

    private static OfferPackageEntity offerPackage(String code) {
        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setCode(code);
        return pkg;
    }

    private static AssortmentGroupEntity group(Long id, BigDecimal referenceQty) {
        AssortmentGroupEntity group = new AssortmentGroupEntity();
        group.setId(id);
        group.setReferenceQty(referenceQty);
        group.setReferenceUnit("szt");
        return group;
    }

    /** A price row carrying a MAX price (the band the headline uses); no qty override by default. */
    private static AssortmentPositionPriceEntity price(AssortmentGroupEntity group,
                                                       OfferPackageEntity pkg, BigDecimal maxPrice) {
        AssortmentPositionEntity position = new AssortmentPositionEntity();
        position.setGroup(group);

        AssortmentPositionPriceEntity price = new AssortmentPositionPriceEntity();
        price.setPosition(position);
        price.setOfferPackage(pkg);
        price.setMaxPrice(maxPrice);
        return price;
    }
}
