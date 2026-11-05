package com.foremen.service.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FOR-07-06 Round 1 / bug E — precedence of {@link CompanyPaymentStatus#of}. Verifies the four
 * classifications and their ordering: NO_ACTIVITY first, then HAS_DEBT, then UNREPORTED_WORK, else
 * FULLY_PAID.
 */
class CompanyPaymentStatusTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("no planned/reported/paid and zero balance ⇒ NO_ACTIVITY")
    void noActivity() {
        assertThat(CompanyPaymentStatus.of(bd("0"), bd("0"), bd("0"), bd("0.00")))
                .isEqualTo(CompanyPaymentStatus.NO_ACTIVITY);
    }

    @Test
    @DisplayName("positive balance ⇒ HAS_DEBT, even when planned > reported")
    void hasDebtWinsOverUnreported() {
        assertThat(CompanyPaymentStatus.of(bd("120.00"), bd("160.00"), bd("50.00"), bd("188.00")))
                .isEqualTo(CompanyPaymentStatus.HAS_DEBT);
    }

    @Test
    @DisplayName("an opening-balance-only (owed) worker with no period activity ⇒ HAS_DEBT, not NO_ACTIVITY")
    void openingDebtOnlyIsHasDebt() {
        assertThat(CompanyPaymentStatus.of(bd("0"), bd("0"), bd("0"), bd("100.00")))
                .isEqualTo(CompanyPaymentStatus.HAS_DEBT);
    }

    @Test
    @DisplayName("planned > reported with no outstanding debt ⇒ UNREPORTED_WORK")
    void unreportedWork() {
        assertThat(CompanyPaymentStatus.of(bd("80.00"), bd("120.00"), bd("80.00"), bd("0.00")))
                .isEqualTo(CompanyPaymentStatus.UNREPORTED_WORK);
    }

    @Test
    @DisplayName("settled balance, nothing left to report ⇒ FULLY_PAID")
    void fullyPaid() {
        assertThat(CompanyPaymentStatus.of(bd("120.00"), bd("120.00"), bd("120.00"), bd("0.00")))
                .isEqualTo(CompanyPaymentStatus.FULLY_PAID);
    }

    @Test
    @DisplayName("negative balance (overpaid) with reported == planned ⇒ FULLY_PAID")
    void overpaidIsFullyPaid() {
        assertThat(CompanyPaymentStatus.of(bd("120.00"), bd("120.00"), bd("130.00"), bd("-10.00")))
                .isEqualTo(CompanyPaymentStatus.FULLY_PAID);
    }

    @Test
    @DisplayName("null figures treated as zero ⇒ NO_ACTIVITY")
    void nullsAreZero() {
        assertThat(CompanyPaymentStatus.of(null, null, null, null))
                .isEqualTo(CompanyPaymentStatus.NO_ACTIVITY);
    }

    @Test
    @DisplayName("scale is irrelevant (compareTo): 0.00 balance ⇒ not HAS_DEBT")
    void scaleIrrelevant() {
        assertThat(CompanyPaymentStatus.of(bd("10.00"), bd("10.00"), bd("10.000"), bd("0.0000")))
                .isEqualTo(CompanyPaymentStatus.FULLY_PAID);
    }
}
