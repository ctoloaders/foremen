package com.foremen.service.procurement.planner;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Pure money math for purchase positions (FOR-11-01, Requirement 4.1).
 *
 * <p>All amounts are PLN {@link BigDecimal}s at scale 2 with {@link RoundingMode#HALF_UP}. Rounding is
 * applied per line; totals are the sum of the already rounded lines, so a total never differs from the
 * sum of the rows shown.
 */
public final class PositionMoneyMath {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private PositionMoneyMath() {
    }

    /**
     * One priced line. {@code vat} is {@code null} when the VAT rate is unknown; {@code gross} then equals
     * {@code net}.
     */
    public record Line(BigDecimal net, BigDecimal vat, BigDecimal gross) {
    }

    /** Sum of rounded lines. */
    public record Totals(BigDecimal net, BigDecimal vat, BigDecimal gross) {
    }

    /**
     * Sale (or any priced) line: {@code net = round2(unitNet × qty)}, {@code vat = round2(net × rate / 100)},
     * {@code gross = net + vat}.
     *
     * @return {@code null} when the unit price or the quantity is {@code null}
     */
    public static Line saleLine(BigDecimal unitNet, BigDecimal quantity, BigDecimal vatRatePercent) {
        BigDecimal net = purchaseNet(unitNet, quantity);
        if (net == null) {
            return null;
        }
        if (vatRatePercent == null) {
            return new Line(net, null, net);
        }
        BigDecimal vat = round2(net.multiply(vatRatePercent).divide(HUNDRED));
        return new Line(net, vat, net.add(vat));
    }

    /** {@code round2(unitNet × quantity)}; {@code null} when either argument is {@code null}. */
    public static BigDecimal purchaseNet(BigDecimal unitNet, BigDecimal quantity) {
        if (unitNet == null || quantity == null) {
            return null;
        }
        return round2(unitNet.multiply(quantity));
    }

    /** Sums the rounded lines; {@code null} lines are skipped and a {@code null} VAT counts as zero. */
    public static Totals sum(List<Line> lines) {
        BigDecimal net = zero();
        BigDecimal vat = zero();
        BigDecimal gross = zero();
        if (lines != null) {
            for (Line line : lines) {
                if (line == null) {
                    continue;
                }
                net = net.add(line.net());
                if (line.vat() != null) {
                    vat = vat.add(line.vat());
                }
                gross = gross.add(line.gross());
            }
        }
        return new Totals(net, vat, gross);
    }

    private static BigDecimal round2(BigDecimal value) {
        return value.setScale(SCALE, ROUNDING);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE);
    }
}
