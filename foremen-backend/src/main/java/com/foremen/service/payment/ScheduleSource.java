package com.foremen.service.payment;

/**
 * How a {@code PaymentSchedule}'s tranches were authored (FOR-06-07, design §Data Models,
 * Requirement 1.2).
 *
 * <ul>
 *   <li>{@link #GENERATED} — produced by the {@link TrancheGenerator} from a strategy + periodicity.</li>
 *   <li>{@link #MANUAL} — authored tranche-by-tranche through the manual scheduler.</li>
 * </ul>
 */
public enum ScheduleSource {
    GENERATED,
    MANUAL
}
