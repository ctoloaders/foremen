package com.foremen.service.payment;

/**
 * Due-date cadence for generated tranches (Requirement 2.1).
 *
 * <p>{@link #WEEKLY}/{@link #BIWEEKLY}/{@link #MONTHLY} step the {@code dueDate} from the start
 * date; {@link #CUSTOM_DATES} takes an explicit list of dates (one per tranche).
 */
public enum Periodicity {
    WEEKLY,
    BIWEEKLY,
    MONTHLY,
    CUSTOM_DATES
}
