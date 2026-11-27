package com.foremen.service.estimate;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.foremen.dao.EstimateLineRoomMaterialDao;
import com.foremen.dao.model.ConsumptionBasis;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.EstimateLineRoomMaterialEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.WorkMaterialConsumptionEntity;
import com.foremen.service.pricing.FinishingPriceRangeResolver;
import com.foremen.service.pricing.PriceRangeResolver;

/**
 * FOR-05-04 task 3.12 (Requirement 23.8): the material seeder scopes the finishing price range by the
 * package when one is given, and keeps the package-less widest band of a manual assign otherwise.
 */
class EstimateMaterialSeederPackageTest {

    private final EstimateMaterialSeeder seeder = new EstimateMaterialSeeder(
            Mockito.mock(EstimateLineRoomMaterialDao.class), new PriceRangeResolver(),
            new FinishingPriceRangeResolver());

    private static MaterialTypeEntity type(long id) {
        MaterialTypeEntity t = new MaterialTypeEntity();
        t.setId(id);
        return t;
    }

    private static OfferPackageEntity pack(long id) {
        OfferPackageEntity p = new OfferPackageEntity();
        p.setId(id);
        return p;
    }

    private static FinishingMaterialEntity product(MaterialTypeEntity type, String retailNet,
                                                   OfferPackageEntity... packages) {
        FinishingMaterialEntity m = new FinishingMaterialEntity();
        m.setType(type);
        m.setRetailNet(new BigDecimal(retailNet));
        m.setActive(true);
        m.setPackages(Set.of(packages));
        return m;
    }

    private static WorkMaterialConsumptionEntity consumption(MaterialTypeEntity type) {
        WorkMaterialConsumptionEntity c = new WorkMaterialConsumptionEntity();
        c.setBranch(ConsumptionBranch.finishing);
        c.setFinishingMaterialType(type);
        c.setNormQty(new BigDecimal("0.5"));
        c.setConsumptionBasis(ConsumptionBasis.PER_UNIT);
        return c;
    }

    private EstimateLineRoomMaterialEntity seedOne(MaterialTypeEntity type, List<FinishingMaterialEntity> catalog,
                                                   Long packageId) {
        EstimateLineRoomQtyEntity cell = new EstimateLineRoomQtyEntity();
        seeder.seed(cell, List.of(consumption(type)), List::of, () -> catalog, packageId);
        assertThat(cell.getMaterials()).hasSize(1);
        return cell.getMaterials().get(0);
    }

    @Test
    @DisplayName("a package narrows the finishing range to its products; no package keeps the widest band")
    void packageScopesTheFinishingRange() {
        MaterialTypeEntity paint = type(7L);
        OfferPackageEntity standard = pack(1L);
        OfferPackageEntity premium = pack(2L);
        List<FinishingMaterialEntity> catalog = List.of(
                product(paint, "10.00", standard), product(paint, "14.00", standard),
                product(paint, "30.00", premium));

        EstimateLineRoomMaterialEntity widest = seedOne(paint, catalog, null);
        assertThat(widest.getRangeMin()).isEqualByComparingTo("10.00");
        assertThat(widest.getRangeMax()).isEqualByComparingTo("30.00");

        EstimateLineRoomMaterialEntity scoped = seedOne(paint, catalog, 1L);
        assertThat(scoped.getRangeMin()).isEqualByComparingTo("10.00");
        assertThat(scoped.getRangeMax()).isEqualByComparingTo("14.00");
        assertThat(scoped.getNormQty()).isEqualByComparingTo("0.5");
        assertThat(scoped.getConsumptionBasis()).isEqualTo(ConsumptionBasis.PER_UNIT);
    }

    @Test
    @DisplayName("a package without a price for the type falls back to the package-less band")
    void packageWithoutPriceFallsBack() {
        MaterialTypeEntity paint = type(7L);
        List<FinishingMaterialEntity> catalog = List.of(product(paint, "12.00", pack(2L)));
        EstimateLineRoomMaterialEntity line = seedOne(paint, catalog, 1L);
        assertThat(line.getRangeMin()).isEqualByComparingTo("12.00");
        assertThat(line.getRangeMax()).isEqualByComparingTo("12.00");
    }
}
