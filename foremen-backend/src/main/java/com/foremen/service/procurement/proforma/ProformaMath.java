package com.foremen.service.procurement.proforma;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.foremen.service.procurement.planner.PositionMoneyMath;

/**
 * FOR-11-03 (R3.4): thin wrapper over {@link PositionMoneyMath} so the proforma gross cannot diverge from
 * the planner selection total for the same positions.
 */
public final class ProformaMath {

    private ProformaMath() {
    }

    public record LineInput(BigDecimal unitSaleNet, BigDecimal quantity, BigDecimal vatRatePercent) {
    }

    /** {@code lines} is index-aligned with the input. */
    public record Result(List<PositionMoneyMath.Line> lines, PositionMoneyMath.Totals totals) {
    }

    public static Result compute(List<LineInput> inputs) {
        List<PositionMoneyMath.Line> lines = new ArrayList<>();
        if (inputs != null) {
            for (LineInput in : inputs) {
                Objects.requireNonNull(in, "line input");
                Objects.requireNonNull(in.unitSaleNet(), "unitSaleNet");
                Objects.requireNonNull(in.quantity(), "quantity");
                Objects.requireNonNull(in.vatRatePercent(), "vatRatePercent");
                lines.add(PositionMoneyMath.saleLine(in.unitSaleNet(), in.quantity(), in.vatRatePercent()));
            }
        }
        return new Result(List.copyOf(lines), PositionMoneyMath.sum(lines));
    }
}
