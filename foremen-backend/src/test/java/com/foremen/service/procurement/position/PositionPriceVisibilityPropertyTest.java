package com.foremen.service.procurement.position;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.position.PositionPriceVisibility.Audience;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** **Validates: Requirements 10.2, 10.3, 10.4** */
class PositionPriceVisibilityPropertyTest {

    @Provide
    Arbitrary<PositionMoney> money() {
        Arbitrary<BigDecimal> price = Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("100000")).ofScale(2).injectNull(0.2);
        Arbitrary<Long> vat = Arbitraries.longs().between(1, 10).injectNull(0.2);
        return Combinators.combine(price, price, vat, price, vat, price).as(PositionMoney::new);
    }

    @Property
    void staffFullSeesAll(@ForAll SourcingPath p, @ForAll PositionStatus s, @ForAll("money") PositionMoney m) {
        assertThat(PositionPriceVisibility.apply(Audience.STAFF_FULL, p, s, m)).isEqualTo(m);
    }

    @Property
    void siteIsAlwaysAllNull(@ForAll SourcingPath p, @ForAll PositionStatus s, @ForAll("money") PositionMoney m) {
        assertThat(PositionPriceVisibility.apply(Audience.SITE, p, s, m)).isEqualTo(PositionMoney.NONE);
    }

    @Property
    void clientNeverSeesPurchaseSideOrNonAgreed(@ForAll SourcingPath p, @ForAll PositionStatus s,
                                                @ForAll("money") PositionMoney m) {
        PositionMoney r = PositionPriceVisibility.apply(Audience.CLIENT, p, s, m);
        assertThat(r.plannedPurchasePrice()).isNull();
        assertThat(r.actualPurchasePrice()).isNull();
        assertThat(r.purchaseVatRateId()).isNull();
        assertThat(r.deliveryCostShare()).isNull();
        if (p != SourcingPath.AGREED) {
            assertThat(r).isEqualTo(PositionMoney.NONE);
        }
        if (r.salePrice() != null) {
            assertThat(p).isEqualTo(SourcingPath.AGREED);
            assertThat(s.ordinal()).isGreaterThanOrEqualTo(PositionStatus.PROPOSED.ordinal());
            assertThat(s).isNotEqualTo(PositionStatus.CANCELLED);
        }
    }

    /** **Validates: Requirements 3.2, 23.1** */
    @Property
    void clientSeesSalePriceOfAgreedFromProposedOnward(@ForAll PositionStatus s, @ForAll("money") PositionMoney m) {
        PositionMoney r = PositionPriceVisibility.apply(Audience.CLIENT, SourcingPath.AGREED, s, m);
        boolean visible = s != PositionStatus.PLANNED && s != PositionStatus.CANCELLED;
        assertThat(r.salePrice()).isEqualTo(visible ? m.salePrice() : null);
    }
}
