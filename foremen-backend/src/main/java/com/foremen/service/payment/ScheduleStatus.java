package com.foremen.service.payment;

/**
 * The lifecycle status of a {@code PaymentSchedule}.
 *
 * <ul>
 *   <li>{@link #DRAFT} — editable, not yet client-visible.</li>
 *   <li>{@link #PUBLISHED} — client-visible (via FOR-09); tranches locked except a re-plan of the
 *       unpaid remainder.</li>
 *   <li>{@link #SETTLED} — every tranche is {@code PAID}; derived, never assigned by hand
 *       (Requirements 1.6, 5.6).</li>
 * </ul>
 */
public enum ScheduleStatus {
    DRAFT,
    PUBLISHED,
    SETTLED
}
