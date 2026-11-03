package com.foremen.service.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure, persistence-free oldest-first distribution of a payout amount over a worker's unpaid
 * report lines (FOR-07-06, Requirements 5.3, 5.4, 5.5; design §PayoutDistributor, Property 3).
 *
 * <p>The caller passes the candidate lines <b>already in distribution order</b> (oldest first, as
 * produced by {@code WorkReportRoomDao.findPayableLines}: {@code performedFrom, performedTo,
 * report id, line id}). Each line receives {@code min(left, remainder)}; distribution stops when the
 * amount is exhausted or the lines run out. Lines that would receive 0 emit no allocation. Whatever
 * is left over is returned as an unallocated {@link Distribution#surplus() surplus} — it is never
 * turned into advance credit (R5.5).
 *
 * <p>Invariants: {@code Σ allocations = min(D, Σ r)}; every allocation {@code ≤} its line's
 * remainder; allocations are a prefix of the (positive-remainder) order; at most the last emitted
 * allocation is partial; {@code surplus = max(0, D − Σ r)}. All amounts are normalized to scale 2,
 * {@link RoundingMode#HALF_UP}; {@code null}/negative inputs are treated as 0, so nothing is
 * negative.
 *
 * <p>No Spring, no clock, no persistence — a deterministic function of its arguments.
 */
public final class PayoutDistributor {

    /** {@code NUMERIC(19,2)} scale, consistent with the pricing and FOR-06-10 cost stack. */
    static final int SCALE = 2;

    private PayoutDistributor() {
    }

    /**
     * One candidate line: the {@code work_report_rooms} row id and its unpaid remainder
     * ({@code max(0, cost − paid_amount)}).
     */
    public record PayableLine(Long lineId, BigDecimal remainder) {
    }

    /** The amount allocated to one line by a distribution. */
    public record Allocation(Long lineId, BigDecimal amount) {
    }

    /** Result of a distribution: allocations in order, plus the unallocated surplus. */
    public record Distribution(List<Allocation> allocations, BigDecimal surplus) {

        public Distribution {
            allocations = List.copyOf(allocations);
        }

        /** Sum of all allocated amounts (scale 2). */
        public BigDecimal allocatedTotal() {
            return allocations.stream()
                    .map(Allocation::amount)
                    .reduce(BigDecimal.ZERO.setScale(SCALE), BigDecimal::add);
        }
    }

    /**
     * Distribute {@code d} over {@code lines} in the given (distribution) order.
     *
     * @param d     the amount to distribute ({@code amount + advanceApplied}); {@code null}/negative → 0
     * @param lines candidate lines in distribution order; {@code null} → no lines
     * @return the allocations (only lines receiving {@code > 0}) and the surplus
     */
    public static Distribution distribute(BigDecimal d, List<PayableLine> lines) {
        BigDecimal left = nonNegative(d);
        List<Allocation> allocations = new ArrayList<>();
        if (lines != null) {
            for (PayableLine line : lines) {
                if (left.signum() <= 0) {
                    break;
                }
                Objects.requireNonNull(line, "line");
                BigDecimal remainder = nonNegative(line.remainder());
                BigDecimal a = left.min(remainder);
                if (a.signum() > 0) {
                    allocations.add(new Allocation(line.lineId(), a));
                    left = left.subtract(a);
                }
            }
        }
        return new Distribution(allocations, left);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
