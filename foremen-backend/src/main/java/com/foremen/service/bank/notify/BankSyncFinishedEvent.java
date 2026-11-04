package com.foremen.service.bank.notify;

/**
 * FOR-07-04 (Requirement 22.2 row 5; design §Notifications {@code BankSyncFinished}) — a
 * {@code Sync_Run} finished. {@link BankNotificationEmitter} notifies ADMIN + FINANCIER + MANAGER only
 * when the run ingested at least one new posted {@code BANK_FEED} transaction that ended in
 * {@code TO_REVIEW} ({@code toReviewCount > 0}).
 *
 * <p>Cash entries (manual or payroll / procurement hooks) never publish this event (R22.5).
 *
 * @param runId         the finished sync run id
 * @param connectionId  the run's bank connection id
 * @param toReviewCount the number of new posted transactions of the run that ended in {@code TO_REVIEW}
 */
public record BankSyncFinishedEvent(Long runId, Long connectionId, int toReviewCount) {
}
