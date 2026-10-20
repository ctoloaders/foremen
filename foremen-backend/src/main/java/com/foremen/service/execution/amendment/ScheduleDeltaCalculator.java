package com.foremen.service.execution.amendment;

import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.foremen.dao.model.AmendmentLineEntity;
import com.foremen.dao.model.ChangeType;
import com.foremen.exception.ForemenApiException;

/**
 * FOR-06-09 (Requirements 5.1, 5.3, 5.5; design §"ScheduleDeltaCalculator (pure)") — the
 * persistence-free arithmetic behind the single schedule mutation an {@code EFFECTIVE} amendment
 * performs: shifting {@code Project.endDate} by the amendment's net schedule delta.
 *
 * <p>It is a pure collaborator (no persistence, no Spring state beyond {@code @Component}) so it can
 * be property-tested in isolation (design §"The amendment reflects the document; it does not
 * re-implement signing"):
 *
 * <ul>
 *   <li>{@link #netHalfDays(List)} — sums the signed {@code scheduleDeltaHalfDays} of the amendment's
 *       {@link ChangeType#SCHEDULE_CHANGE} lines (positive = extend, negative = shorten); cost lines
 *       and {@code SCHEDULE_CHANGE} lines with a {@code null} delta contribute nothing. No
 *       {@code SCHEDULE_CHANGE} line ⇒ {@code 0} ⇒ the end date is later left unchanged (R5.1, R5.3).</li>
 *   <li>{@link #shift(LocalDate, int)} — converts half-days to days on the FOR-06-08 half-day grid
 *       ({@code halfDays / 2}, rounded) and adds them to the end date (R5.1).</li>
 *   <li>{@link #assertValidRange(LocalDate, LocalDate)} — the R5.5 guard: a shift that would move the
 *       end date <b>before</b> the project start date is <b>rejected</b> (not clamped) with a
 *       {@code 409 error.amendment.endDateInvalid} so an invalid range never silently persists.</li>
 * </ul>
 */
@Component
public class ScheduleDeltaCalculator {

    /** Localized message code for a shift that would drive the end date before the start date (R5.5). */
    public static final String END_DATE_INVALID = "error.amendment.endDateInvalid";

    /**
     * The net schedule delta = Σ {@code scheduleDeltaHalfDays} over the amendment's
     * {@link ChangeType#SCHEDULE_CHANGE} lines, skipping {@code null} deltas (Requirement 5.1). A
     * {@code null} or empty line list, or an amendment with no {@code SCHEDULE_CHANGE} line, yields
     * {@code 0} (Requirement 5.3).
     *
     * @param lines the amendment's change lines (may be {@code null} / empty)
     * @return the signed net half-day delta (positive = extend, negative = shorten)
     */
    public int netHalfDays(List<AmendmentLineEntity> lines) {
        if (lines == null || lines.isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (AmendmentLineEntity line : lines) {
            if (line == null || line.getChangeType() != ChangeType.SCHEDULE_CHANGE) {
                continue;
            }
            Integer delta = line.getScheduleDeltaHalfDays();
            if (delta != null) {
                sum += delta;
            }
        }
        return sum;
    }

    /**
     * Shifts {@code endDate} by {@code netHalfDays} converted to whole days on the half-day grid:
     * {@code endDate.plusDays(round(netHalfDays / 2.0))} (Requirement 5.1). A zero delta leaves the
     * date unchanged.
     *
     * @param endDate     the current project end date (non-{@code null})
     * @param netHalfDays the signed net half-day delta (from {@link #netHalfDays(List)})
     * @return the shifted end date
     */
    public LocalDate shift(LocalDate endDate, int netHalfDays) {
        long days = Math.round(netHalfDays / 2.0);
        return endDate.plusDays(days);
    }

    /**
     * Rejects a shift that would move the end date before the project start date (Requirement 5.5).
     * Pure and side-effect-free: it either returns normally (valid range) or throws. The boundary case
     * {@code shiftedEnd == startDate} is valid (a zero-length schedule is allowed).
     *
     * @param startDate  the project start date (non-{@code null})
     * @param shiftedEnd the prospective shifted end date (non-{@code null})
     * @throws ForemenApiException {@code 409 error.amendment.endDateInvalid} if {@code shiftedEnd}
     *                             falls before {@code startDate}
     */
    public void assertValidRange(LocalDate startDate, LocalDate shiftedEnd) {
        if (shiftedEnd.isBefore(startDate)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, END_DATE_INVALID);
        }
    }
}
