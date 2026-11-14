package com.foremen.controller.model.client;

import java.math.BigDecimal;

/** FOR-11-02: client-visible money (PLN, sale side only). */
public record ClientMoneyDto(BigDecimal unitNet, BigDecimal vatRatePercent, BigDecimal unitGross,
                             BigDecimal lineNet, BigDecimal lineGross) {
}
