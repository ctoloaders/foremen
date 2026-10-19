package com.foremen.service.payment;

/**
 * The lifecycle status of a single {@code PaymentTranche}, derived purely from
 * {@code (paidGross, amountGross, dueDate, now)} by {@link TrancheStatusDeriver} and never set by
 * hand (Requirements 10.1, 10.3).
 *
 * <ul>
 *   <li>{@link #PENDING} — not yet due, unpaid.</li>
 *   <li>{@link #DUE} — due date reached, unpaid.</li>
 *   <li>{@link #PARTIALLY_PAID} — {@code 0 < paidGross < amountGross}.</li>
 *   <li>{@link #PAID} — {@code paidGross >= amountGross}.</li>
 *   <li>{@link #OVERDUE} — past {@code dueDate} and not {@code PAID}.</li>
 * </ul>
 */
public enum TrancheStatus {
    PENDING,
    DUE,
    PARTIALLY_PAID,
    PAID,
    OVERDUE
}
