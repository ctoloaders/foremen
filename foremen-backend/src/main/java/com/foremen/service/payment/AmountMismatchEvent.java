package com.foremen.service.payment;

/**
 * FOR-06-07 (Requirement 9.2 row 4): the transition event raised when a confirmed payment results in
 * an under/overpayment — a shortfall (the nearest open tranche is left {@code PARTIALLY_PAID}) or a
 * carry-over (surplus cascaded forward and/or held as unallocated credit). Published by
 * {@code ReconciliationService} when a reconciliation does not settle the allocated amount cleanly
 * into exact tranche boundaries, and consumed by the best-effort {@code PaymentNotificationEmitter}
 * (task 5.6) via an {@code AFTER_COMMIT} {@code @TransactionalEventListener}, so an emission failure
 * never rolls back the reconciliation transition (R9.5).
 *
 * <p>Mirrors the sibling {@link TrancheOverdueEvent} convention — a plain immutable record carrying
 * only ids. The emitter resolves the recipients (MANAGER + FINANCIER of the owning project) and the
 * Płatności deep-link from these ids.
 *
 * @param scheduleId the schedule on which the mismatch occurred
 * @param projectId  the owning project id (used to resolve the MANAGER + FINANCIER recipients)
 */
public record AmountMismatchEvent(Long scheduleId, Long projectId) {
}
