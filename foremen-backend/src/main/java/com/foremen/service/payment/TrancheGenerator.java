package com.foremen.service.payment;

import com.foremen.exception.ForemenApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Splits a schedule basis into a sum-exact, contiguously-numbered list of {@link GeneratedTranche}s
 * (Requirement 2).
 *
 * <p>Pure and persistence-free: no repositories, no clock. The due date of each tranche is derived
 * solely from the supplied {@code startDate}/{@code periodicity} (or the explicit
 * {@code customDates}), never from {@code now}, so the function is fully property-testable.
 *
 * <p>Monetary rounding uses {@link RoundingMode#HALF_UP} to 2 decimals, matching the pricing stack;
 * the rounding remainder is always pushed onto the LAST tranche so
 * {@code Σ amountGross == basisGross} exactly (Requirements 2.2, 2.3).
 */
@Component
public class TrancheGenerator {

    /** Rejects malformed input (bad N, negative amount/percentage, absent start date) — R2.5. */
    static final String INVALID_MESSAGE = "error.payment.generator.invalid";

    /** Rejects a {@link GeneratorStrategy#PERCENT} list that does not sum to exactly 100 — R2.3. */
    static final String PERCENT_SUM_MESSAGE = "error.payment.generator.percentSum";

    /**
     * Rejects a {@link GeneratorStrategy#CUSTOM} list that does not sum to exactly
     * {@code basisGross} — R2.4.
     */
    static final String AMOUNT_SUM_MESSAGE = "error.payment.generator.amountSum";

    private static final int SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * Generate the tranche set (Requirement 2.1).
     *
     * @param basisGross  the total being scheduled (non-null, non-negative)
     * @param strategy    the split strategy (non-null)
     * @param parts       for {@link GeneratorStrategy#EQUAL} the element count defines N (its values
     *                    are ignored and may be null); for {@link GeneratorStrategy#PERCENT} an
     *                    ordered list of percentages summing to 100; for
     *                    {@link GeneratorStrategy#CUSTOM} an ordered list of amounts summing to
     *                    {@code basisGross}
     * @param startDate   the first due date; the anchor for stepped periodicities (required unless
     *                    {@code periodicity == CUSTOM_DATES})
     * @param periodicity the due-date cadence (non-null)
     * @param customDates the explicit per-tranche due dates when
     *                    {@code periodicity == CUSTOM_DATES} (one per tranche)
     * @return the generated tranches, {@code seqNo} {@code 1..N} ordered by {@code dueDate},
     *         {@code Σ amountGross == basisGross}
     * @throws ForemenApiException {@code 422} with a localized code when the input is invalid
     */
    public List<GeneratedTranche> generate(BigDecimal basisGross,
                                           GeneratorStrategy strategy,
                                           List<BigDecimal> parts,
                                           LocalDate startDate,
                                           Periodicity periodicity,
                                           List<LocalDate> customDates) {
        if (basisGross == null || basisGross.signum() < 0 || strategy == null || periodicity == null) {
            throw invalid();
        }

        List<BigDecimal> amounts = switch (strategy) {
            case EQUAL -> equalAmounts(basisGross, partCount(parts));
            case PERCENT -> percentAmounts(basisGross, parts);
            case CUSTOM -> customAmounts(basisGross, parts);
        };

        int n = amounts.size();
        if (n < 1) {
            throw invalid();
        }

        List<LocalDate> dueDates = dueDates(n, startDate, periodicity, customDates);

        List<GeneratedTranche> tranches = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            tranches.add(new GeneratedTranche(i + 1, amounts.get(i), dueDates.get(i)));
        }
        return tranches;
    }

    private static int partCount(List<BigDecimal> parts) {
        return parts == null ? 0 : parts.size();
    }

    /**
     * {@link GeneratorStrategy#EQUAL}: N parts of {@code round(basisGross / N)} with the rounding
     * remainder on the last so the set sums to exactly {@code basisGross} (Requirement 2.2).
     */
    private List<BigDecimal> equalAmounts(BigDecimal basisGross, int n) {
        if (n < 1) {
            throw invalid();
        }
        BigDecimal base = basisGross.divide(BigDecimal.valueOf(n), SCALE, RoundingMode.HALF_UP);
        List<BigDecimal> amounts = new ArrayList<>(n);
        BigDecimal running = BigDecimal.ZERO;
        for (int i = 0; i < n - 1; i++) {
            amounts.add(base);
            running = running.add(base);
        }
        amounts.add(basisGross.setScale(SCALE, RoundingMode.HALF_UP).subtract(running));
        return amounts;
    }

    /**
     * {@link GeneratorStrategy#PERCENT}: reject unless the percentages sum to exactly 100, then
     * {@code round(basisGross × pct / 100)} per tranche with the remainder on the last (R2.3).
     */
    private List<BigDecimal> percentAmounts(BigDecimal basisGross, List<BigDecimal> parts) {
        if (parts == null || parts.isEmpty()) {
            throw invalid();
        }
        BigDecimal pctSum = BigDecimal.ZERO;
        for (BigDecimal pct : parts) {
            if (pct == null || pct.signum() < 0) {
                throw invalid();
            }
            pctSum = pctSum.add(pct);
        }
        if (pctSum.compareTo(HUNDRED) != 0) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, PERCENT_SUM_MESSAGE);
        }

        int n = parts.size();
        BigDecimal basis = basisGross.setScale(SCALE, RoundingMode.HALF_UP);
        List<BigDecimal> amounts = new ArrayList<>(n);
        BigDecimal running = BigDecimal.ZERO;
        for (int i = 0; i < n - 1; i++) {
            BigDecimal amount = basisGross.multiply(parts.get(i))
                    .divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
            amounts.add(amount);
            running = running.add(amount);
        }
        amounts.add(basis.subtract(running));
        return amounts;
    }

    /**
     * {@link GeneratorStrategy#CUSTOM}: the explicit amounts, rejected unless they sum to exactly
     * {@code basisGross} (Requirement 2.4).
     */
    private List<BigDecimal> customAmounts(BigDecimal basisGross, List<BigDecimal> parts) {
        if (parts == null || parts.isEmpty()) {
            throw invalid();
        }
        BigDecimal sum = BigDecimal.ZERO;
        List<BigDecimal> amounts = new ArrayList<>(parts.size());
        for (BigDecimal amount : parts) {
            if (amount == null || amount.signum() < 0) {
                throw invalid();
            }
            BigDecimal scaled = amount.setScale(SCALE, RoundingMode.HALF_UP);
            amounts.add(scaled);
            sum = sum.add(scaled);
        }
        if (sum.compareTo(basisGross.setScale(SCALE, RoundingMode.HALF_UP)) != 0) {
            throw new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, AMOUNT_SUM_MESSAGE);
        }
        return amounts;
    }

    /**
     * Assign one due date per tranche: an explicit list for {@link Periodicity#CUSTOM_DATES}, or a
     * stepped sequence anchored on {@code startDate} otherwise (Requirement 2.1).
     */
    private List<LocalDate> dueDates(int n,
                                     LocalDate startDate,
                                     Periodicity periodicity,
                                     List<LocalDate> customDates) {
        if (periodicity == Periodicity.CUSTOM_DATES) {
            if (customDates == null || customDates.size() != n) {
                throw invalid();
            }
            List<LocalDate> dates = new ArrayList<>(n);
            for (LocalDate date : customDates) {
                if (date == null) {
                    throw invalid();
                }
                dates.add(date);
            }
            return dates;
        }

        if (startDate == null) {
            throw invalid();
        }
        List<LocalDate> dates = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            dates.add(step(startDate, periodicity, i));
        }
        return dates;
    }

    private static LocalDate step(LocalDate startDate, Periodicity periodicity, int index) {
        return switch (periodicity) {
            case WEEKLY -> startDate.plusWeeks(index);
            case BIWEEKLY -> startDate.plusWeeks(2L * index);
            case MONTHLY -> startDate.plusMonths(index);
            case CUSTOM_DATES -> throw invalid();
        };
    }

    private static ForemenApiException invalid() {
        return new ForemenApiException(HttpStatus.UNPROCESSABLE_ENTITY, INVALID_MESSAGE);
    }
}
