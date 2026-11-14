package com.foremen.service.procurement.approval;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Client money (FOR-11-02, R4.4): PLN, {@code HALF_UP}, scale 2, rounded per line, sale side only.
 *
 * <p>{@code unitGross = round2(unitNet * (1 + vat/100))}, {@code lineNet = round2(unitNet * qty)},
 * {@code lineGross = lineNet + round2(lineNet * vat/100)}. A {@code null} VAT rate is treated as 0.
 */
public final class ClientMoneyCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private ClientMoneyCalculator() {
    }

    /** Computed values; the later client DTO is built from this record. */
    public record Money(BigDecimal unitNet, BigDecimal vatRatePercent, BigDecimal unitGross,
                        BigDecimal lineNet, BigDecimal lineGross) {
    }

    /** @return {@code null} when the unit price or the quantity is {@code null} */
    public static Money calculate(BigDecimal unitNet, BigDecimal vatRatePercent, BigDecimal quantity) {
        if (unitNet == null || quantity == null) {
            return null;
        }
        BigDecimal vat = vatRatePercent == null ? BigDecimal.ZERO : vatRatePercent;
        BigDecimal unitNet2 = round2(unitNet);
        BigDecimal unitGross = round2(unitNet.add(unitNet.multiply(vat).divide(HUNDRED)));
        BigDecimal lineNet = round2(unitNet.multiply(quantity));
        BigDecimal lineGross = lineNet.add(round2(lineNet.multiply(vat).divide(HUNDRED)));
        return new Money(unitNet2, round2(vat), unitGross, lineNet, lineGross);
    }

    private static BigDecimal round2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
