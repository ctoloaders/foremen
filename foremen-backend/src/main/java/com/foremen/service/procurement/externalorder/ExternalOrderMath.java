package com.foremen.service.procurement.externalorder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.foremen.service.procurement.planner.PositionMoneyMath;

/**
 * FOR-11-03 (R11.5): pure derived amounts of an external order. Lines use
 * {@link PositionMoneyMath#saleLine}, so rounding matches the rest of FOR-11. The delivery cost is one
 * more priced line (quantity 1).
 */
public final class ExternalOrderMath {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private ExternalOrderMath() {
    }

    /** One purchase line input; {@code vatPercent} null means unknown VAT (gross = net). */
    public record LineInput(BigDecimal unitPurchaseNet, BigDecimal quantity, BigDecimal vatPercent) {
    }

    public record Result(BigDecimal linesNet, BigDecimal linesVat, BigDecimal linesGross,
                         BigDecimal deliveryGross, BigDecimal payableGross) {
    }

    public static Result compute(List<LineInput> lines, BigDecimal deliveryNet, BigDecimal deliveryVatPercent) {
        List<PositionMoneyMath.Line> priced = new ArrayList<>();
        if (lines != null) {
            for (LineInput l : lines) {
                if (l != null) {
                    priced.add(PositionMoneyMath.saleLine(l.unitPurchaseNet(), l.quantity(), l.vatPercent()));
                }
            }
        }
        PositionMoneyMath.Totals totals = PositionMoneyMath.sum(priced);
        BigDecimal deliveryGross = ZERO;
        if (deliveryNet != null && deliveryNet.signum() > 0) {
            PositionMoneyMath.Line d = PositionMoneyMath.saleLine(deliveryNet, BigDecimal.ONE, deliveryVatPercent);
            deliveryGross = d.gross();
        }
        return new Result(totals.net(), totals.vat(), totals.gross(), deliveryGross,
                totals.gross().add(deliveryGross));
    }
}
