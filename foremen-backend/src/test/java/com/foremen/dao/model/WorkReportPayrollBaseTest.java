package com.foremen.dao.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FOR-04-11: payroll base = frozen internal price when set, otherwise the client unit price. */
class WorkReportPayrollBaseTest {

    @Test
    @DisplayName("no internal price: the client unit price is the payroll base")
    void fallsBackToClientPrice() {
        WorkReportEntity report = new WorkReportEntity();
        report.setClientUnitPrice(new BigDecimal("100.00"));

        assertThat(report.payrollBase()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("internal price set: it replaces the client unit price")
    void internalPriceWins() {
        WorkReportEntity report = new WorkReportEntity();
        report.setClientUnitPrice(new BigDecimal("100.00"));
        report.setPayrollBasePrice(new BigDecimal("60.00"));

        assertThat(report.payrollBase()).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("an internal price of zero is a real value, not 'unset'")
    void zeroInternalPriceIsKept() {
        WorkReportEntity report = new WorkReportEntity();
        report.setClientUnitPrice(new BigDecimal("100.00"));
        report.setPayrollBasePrice(BigDecimal.ZERO);

        assertThat(report.payrollBase()).isEqualByComparingTo("0");
    }
}
