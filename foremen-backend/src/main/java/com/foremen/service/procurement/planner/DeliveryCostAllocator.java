package com.foremen.service.procurement.planner;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Splits a delivery cost across positions proportionally to their weights using the largest-remainder
 * method on cents (FOR-11-01, Requirement 4.3). The shares always sum exactly to the total.
 */
public final class DeliveryCostAllocator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private DeliveryCostAllocator() {
    }

    /**
     * @param total   amount to split (rounded to cents HALF_UP first)
     * @param weights weight per position (typically the purchase line net); {@code null} and negative
     *                values count as zero
     * @return shares at scale 2, same order and size as {@code weights}; empty for an empty list
     */
    public static List<BigDecimal> allocate(BigDecimal total, List<BigDecimal> weights) {
        if (weights == null || weights.isEmpty()) {
            return new ArrayList<>();
        }
        int n = weights.size();
        long totalCents = total == null ? 0L
                : total.multiply(HUNDRED).setScale(0, RoundingMode.HALF_UP).longValueExact();
        boolean negative = totalCents < 0;
        long cents = Math.abs(totalCents);

        List<BigDecimal> w = new ArrayList<>(n);
        BigDecimal sumW = BigDecimal.ZERO;
        for (BigDecimal weight : weights) {
            BigDecimal v = weight == null || weight.signum() < 0 ? BigDecimal.ZERO : weight;
            w.add(v);
            sumW = sumW.add(v);
        }

        long[] shares = new long[n];
        if (sumW.signum() == 0) {
            long base = cents / n;
            long rest = cents % n;
            for (int i = 0; i < n; i++) {
                shares[i] = base + (i < rest ? 1 : 0);
            }
        } else {
            BigDecimal[] remainders = new BigDecimal[n];
            BigDecimal centsBd = BigDecimal.valueOf(cents);
            long assigned = 0;
            for (int i = 0; i < n; i++) {
                BigDecimal numerator = centsBd.multiply(w.get(i));
                BigDecimal floor = numerator.divideToIntegralValue(sumW);
                remainders[i] = numerator.subtract(floor.multiply(sumW));
                shares[i] = floor.longValueExact();
                assigned += shares[i];
            }
            long left = cents - assigned;
            List<Integer> order = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                order.add(i);
            }
            order.sort(Comparator.<Integer, BigDecimal>comparing(i -> remainders[i]).reversed()
                    .thenComparingInt(i -> i));
            for (int k = 0; k < left; k++) {
                shares[order.get(k)]++;
            }
        }

        List<BigDecimal> result = new ArrayList<>(n);
        for (long share : shares) {
            result.add(BigDecimal.valueOf(negative ? -share : share, 2));
        }
        return result;
    }
}
