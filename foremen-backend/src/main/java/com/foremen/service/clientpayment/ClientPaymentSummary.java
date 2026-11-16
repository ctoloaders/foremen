package com.foremen.service.clientpayment;

import java.math.BigDecimal;

/** FOR-11-06 (R2.2): totals of the filtered client-payment set (not of the current page). */
public record ClientPaymentSummary(long count, BigDecimal amountGross, BigDecimal paidGross,
                                   BigDecimal remainingGross) {
}
