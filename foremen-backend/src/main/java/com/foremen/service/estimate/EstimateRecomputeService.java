package com.foremen.service.estimate;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;

/**
 * Derives every line's {@code quantity}/{@code valueNet} and the owning estimate's
 * {@code totalNet}/{@code totalVat}/{@code totalGross} from the current room quantities and
 * unit prices (FOR-05-03, Requirement 8; design §6.1 {@code recomputeEstimate}).
 *
 * <p>A stateless Spring {@code @Component}, mirroring the {@code DiscountCalculator}/
 * {@code EffectivePriceResolver}/{@code EstimateLineRoomQtyValidator} convention: it holds no
 * state and performs no I/O — it only mutates the entity graph passed to it in place. It does
 * not persist anything itself; the caller's transaction (a future {@code EstimateLineService}/
 * {@code EstimateLineRoomQtyService} write path, task 12) is responsible for saving the mutated
 * {@link EstimateEntity} and its {@link EstimateLineEntity} rows once this method returns.
 *
 * <p>Rule (Requirements 2.6, 2.7, 3.3, 3.4, 8.1, 8.2, 8.3, 8.5; design §6.1):
 * <ol>
 *   <li>For every line, {@code line.quantity = Σ roomQty.quantity} over the line's room
 *       quantities (R2.6, R3.3); a line with no room quantities derives {@code quantity = 0}
 *       (R3.4).</li>
 *   <li>For every line, {@code line.valueNet = round2(line.unitPrice × line.quantity)}
 *       (R2.7, R8.1).</li>
 *   <li>{@code estimate.totalNet = round2(Σ line.valueNet)} (R8.2).</li>
 *   <li>{@code estimate.totalVat = round2(totalNet × vat / 100)}, where {@code vat} is
 *       {@code coalesce(estimate.vatRate.rate, 0)} expressed as a percentage (e.g. {@code 23.00}
 *       for 23%, matching {@link com.foremen.dao.model.VatRateEntity#getRate()}'s stored shape and
 *       the {@code DiscountCalculator} PERCENT convention) (R8.3).</li>
 *   <li>{@code estimate.totalGross = round2(totalNet + totalVat)} (R8.3).</li>
 *   <li>An estimate with no lines derives {@code totalNet = totalVat = totalGross = 0}
 *       (R8.5).</li>
 * </ol>
 */
@Component
public class EstimateRecomputeService {

    private static final int SCALE = 2;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * Recomputes every line's {@code quantity}/{@code valueNet} from its room quantities and
     * unit price, then recomputes the estimate's {@code totalNet}/{@code totalVat}/
     * {@code totalGross} from its lines, per design §6.1. Mutates {@code estimate} and its
     * lines in place; does not persist.
     *
     * @param estimate the estimate to recompute, with {@code lines} (and each line's
     *                 {@code roomQtys}) already loaded/resolved
     * @return the same {@code estimate} instance, mutated in place, for convenient chaining
     */
    public EstimateEntity recomputeEstimate(EstimateEntity estimate) {
        BigDecimal totalNet = BigDecimal.ZERO;

        for (EstimateLineEntity line : estimate.getLines()) {
            BigDecimal quantity = BigDecimal.ZERO;
            for (EstimateLineRoomQtyEntity roomQty : line.getRoomQtys()) {
                if (roomQty.getQuantity() != null) {
                    quantity = quantity.add(roomQty.getQuantity());
                }
            }
            line.setQuantity(quantity);

            BigDecimal unitPrice = line.getUnitPrice() != null ? line.getUnitPrice() : BigDecimal.ZERO;
            BigDecimal valueNet = round2(unitPrice.multiply(quantity));
            line.setValueNet(valueNet);

            totalNet = totalNet.add(valueNet);
        }

        BigDecimal vat = estimate.getVatRate() != null && estimate.getVatRate().getRate() != null
                ? estimate.getVatRate().getRate()
                : BigDecimal.ZERO;

        BigDecimal roundedTotalNet = round2(totalNet);
        BigDecimal totalVat = round2(roundedTotalNet.multiply(vat).divide(HUNDRED, 10, RoundingMode.HALF_UP));
        BigDecimal totalGross = round2(roundedTotalNet.add(totalVat));

        estimate.setTotalNet(roundedTotalNet);
        estimate.setTotalVat(totalVat);
        estimate.setTotalGross(totalGross);

        return estimate;
    }

    private BigDecimal round2(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
