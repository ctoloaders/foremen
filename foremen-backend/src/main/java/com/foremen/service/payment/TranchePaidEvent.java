package com.foremen.service.payment;

/**
 * FOR-06-07 (Requirement 9.2 row 2): the transition event raised when a tranche reaches
 * {@code PAID} — whether through the nearest-open cascade of a confirmed payment or a manual
 * mark-tranche-paid. Published by {@code ReconciliationService} for each tranche that newly settles,
 * and consumed by the best-effort {@code PaymentNotificationEmitter} (task 5.6) via an
 * {@code AFTER_COMMIT} {@code @TransactionalEventListener}, so an emission failure never rolls back
 * the settlement transition (R9.5).
 *
 * <p>Mirrors the sibling {@link TrancheOverdueEvent} convention — a plain immutable record carrying
 * only ids. The emitter resolves the recipients (MANAGER + FINANCIER of the owning project) and the
 * Płatności deep-link from these ids.
 *
 * @param trancheId  the tranche that reached {@code PAID}
 * @param scheduleId the owning schedule id
 * @param projectId  the owning project id (used to resolve the MANAGER + FINANCIER recipients)
 */
public record TranchePaidEvent(Long trancheId, Long scheduleId, Long projectId) {
}
