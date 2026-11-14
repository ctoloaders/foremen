package com.foremen.service.procurement.position;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.position.PositionPriceVisibility.Audience;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PositionPriceVisibilityTest {

    private static final PositionMoney RAW = new PositionMoney(
            new BigDecimal("1.00"), new BigDecimal("2.00"), 1L, new BigDecimal("3.00"), 2L, new BigDecimal("4.00"));

    @Test
    void staffFullSeesEverything() {
        assertThat(PositionPriceVisibility.apply(Audience.STAFF_FULL, SourcingPath.PACKAGE, PositionStatus.PLANNED, RAW))
                .isEqualTo(RAW);
    }

    @Test
    void siteSeesNothing() {
        assertThat(PositionPriceVisibility.apply(Audience.SITE, SourcingPath.AGREED, PositionStatus.RECEIVED, RAW))
                .isEqualTo(PositionMoney.NONE);
    }

    @Test
    void clientSeesOnlySalePriceForAgreedAcceptedOrLater() {
        PositionMoney m = PositionPriceVisibility.apply(Audience.CLIENT, SourcingPath.AGREED, PositionStatus.ACCEPTED, RAW);
        assertThat(m).isEqualTo(new PositionMoney(null, null, null, RAW.salePrice(), null, null));
    }

    @Test
    void clientSeesSalePriceOfAgreedAtProposed() {
        assertThat(PositionPriceVisibility.apply(Audience.CLIENT, SourcingPath.AGREED, PositionStatus.PROPOSED, RAW))
                .isEqualTo(new PositionMoney(null, null, null, RAW.salePrice(), null, null));
    }

    @Test
    void clientSeesNothingBeforeProposedOrWhenCancelled() {
        assertThat(PositionPriceVisibility.apply(Audience.CLIENT, SourcingPath.AGREED, PositionStatus.PLANNED, RAW))
                .isEqualTo(PositionMoney.NONE);
        assertThat(PositionPriceVisibility.apply(Audience.CLIENT, SourcingPath.AGREED, PositionStatus.CANCELLED, RAW))
                .isEqualTo(PositionMoney.NONE);
    }

    @Test
    void clientSeesNothingForPackageAndClientOwn() {
        assertThat(PositionPriceVisibility.apply(Audience.CLIENT, SourcingPath.PACKAGE, PositionStatus.RECEIVED, RAW))
                .isEqualTo(PositionMoney.NONE);
        assertThat(PositionPriceVisibility.apply(Audience.CLIENT, SourcingPath.CLIENT_OWN, PositionStatus.RECEIVED, RAW))
                .isEqualTo(PositionMoney.NONE);
        assertThat(PositionPriceVisibility.apply(Audience.CLIENT, SourcingPath.PACKAGE, PositionStatus.PROPOSED, RAW))
                .isEqualTo(PositionMoney.NONE);
        assertThat(PositionPriceVisibility.apply(Audience.CLIENT, SourcingPath.CLIENT_OWN, PositionStatus.PROPOSED, RAW))
                .isEqualTo(PositionMoney.NONE);
    }
}
