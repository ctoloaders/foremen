package com.foremen.service.payment;

/**
 * FOR-06-07 (Requirement 9.2 row 3, 9.3, 10.2): the transition event raised when a tranche
 * <b>first</b> crosses into {@link TrancheStatus#OVERDUE}. Published by {@code OverdueSweepService}
 * on the first-crossing of each tranche (guarded by the persisted {@code overdueNotifiedAt} marker so
 * it fires exactly once per tranche) and consumed by the best-effort {@code PaymentNotificationEmitter}
 * (task 5.6) via an {@code AFTER_COMMIT} {@code @TransactionalEventListener}, so an emission failure
 * never rolls back the sweep transition (R9.5).
 *
 * <p>Mirrors the sibling {@link PaymentTransactionService.OnlineFailedEvent} convention — a plain
 * immutable record published through {@code ApplicationEventPublisher}; the emitter resolves the
 * recipients (MANAGER + FINANCIER of the owning project) and the Płatności deep-link from the ids.
 *
 * @param trancheId  the tranche that first crossed into {@code OVERDUE}
 * @param scheduleId the owning schedule id
 */
public record TrancheOverdueEvent(Long trancheId, Long scheduleId) {
}
