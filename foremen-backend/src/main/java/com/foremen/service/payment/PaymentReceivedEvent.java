package com.foremen.service.payment;

/**
 * FOR-06-07 (Requirement 9.2 row 1): the transition event raised when a {@code PaymentTransaction}
 * becomes {@code CONFIRMED} and is allocated against a schedule. Published by
 * {@code ReconciliationService} once a confirmed transaction has been reconciled, and consumed by the
 * best-effort {@code PaymentNotificationEmitter} (task 5.6) via an {@code AFTER_COMMIT}
 * {@code @TransactionalEventListener}, so an emission failure never rolls back the reconciliation
 * transition (R9.5).
 *
 * <p>Mirrors the sibling {@link TrancheOverdueEvent} / {@link PaymentTransactionService.OnlineFailedEvent}
 * convention — a plain immutable record carrying only ids (never a loaded entity, so it stays valid
 * on the after-commit thread). The emitter resolves the recipients (MANAGER + FINANCIER of the owning
 * project) and the Płatności deep-link from these ids.
 *
 * @param scheduleId the schedule the confirmed payment was allocated against
 * @param projectId  the owning project id (used to resolve the MANAGER + FINANCIER recipients)
 */
public record PaymentReceivedEvent(Long scheduleId, Long projectId) {
}
