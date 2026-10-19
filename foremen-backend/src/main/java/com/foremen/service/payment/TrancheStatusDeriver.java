package com.foremen.service.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Derives payment statuses purely from the current figures and the clock (Requirements 10.1, 10.3,
 * 1.6, 5.6).
 *
 * <p>Pure and persistence-free: no repositories and no injected clock — {@code now} is always a
 * parameter — so every derivation is deterministic and property-testable in isolation. The stored
 * {@code status} columns on the schedule/tranche are a materialized cache written only by this
 * routine; a status is never hand-set (Requirement 10.3).
 */
@Component
public class TrancheStatusDeriver {

    /**
     * Derive a tranche status in the exact Requirement 10.1 order:
     *
     * <ol>
     *   <li>{@link TrancheStatus#PAID} if {@code paidGross >= amountGross};</li>
     *   <li>else {@link TrancheStatus#OVERDUE} if {@code now > dueDate};</li>
     *   <li>else {@link TrancheStatus#PARTIALLY_PAID} if {@code paidGross > 0};</li>
     *   <li>else {@link TrancheStatus#DUE} if {@code now >= dueDate};</li>
     *   <li>else {@link TrancheStatus#PENDING}.</li>
     * </ol>
     *
     * <p>The clock instant is compared at day granularity against {@code dueDate} using UTC, so the
     * function is total and deterministic: {@code now > dueDate} means the UTC date of {@code now}
     * falls strictly after {@code dueDate}; {@code now >= dueDate} means on or after.
     *
     * @param paidGross   the running settled total on the tranche (non-null)
     * @param amountGross the tranche's full amount (non-null)
     * @param dueDate     the tranche's due date (non-null)
     * @param now         the evaluation instant (non-null)
     * @return the derived {@link TrancheStatus}
     */
    public TrancheStatus derive(BigDecimal paidGross, BigDecimal amountGross,
                                LocalDate dueDate, Instant now) {
        if (paidGross.compareTo(amountGross) >= 0) {
            return TrancheStatus.PAID;
        }
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        if (today.isAfter(dueDate)) {
            return TrancheStatus.OVERDUE;
        }
        if (paidGross.signum() > 0) {
            return TrancheStatus.PARTIALLY_PAID;
        }
        if (!today.isBefore(dueDate)) {
            return TrancheStatus.DUE;
        }
        return TrancheStatus.PENDING;
    }

    /**
     * Derive the schedule status (Requirements 1.6, 5.6): {@link ScheduleStatus#SETTLED} iff the
     * tranche set is non-empty and every tranche is {@link TrancheStatus#PAID}; otherwise the
     * schedule retains its current non-settled status ({@link ScheduleStatus#DRAFT} /
     * {@link ScheduleStatus#PUBLISHED}).
     *
     * <p>If the current status is already {@code SETTLED} but the tranche set is no longer
     * all-{@code PAID} (e.g. after a re-plan or a manual add re-opens the remainder), the schedule
     * flips back to {@code PUBLISHED} — a schedule can only be {@code SETTLED} while client-visible.
     *
     * @param trancheStatuses the current per-tranche statuses (non-null)
     * @param current         the schedule's current status (non-null)
     * @return the derived {@link ScheduleStatus}
     */
    public ScheduleStatus deriveSchedule(List<TrancheStatus> trancheStatuses, ScheduleStatus current) {
        if (allPaid(trancheStatuses)) {
            return ScheduleStatus.SETTLED;
        }
        return current == ScheduleStatus.SETTLED ? ScheduleStatus.PUBLISHED : current;
    }

    private static boolean allPaid(List<TrancheStatus> trancheStatuses) {
        if (trancheStatuses.isEmpty()) {
            return false;
        }
        for (TrancheStatus status : trancheStatuses) {
            if (status != TrancheStatus.PAID) {
                return false;
            }
        }
        return true;
    }
}
