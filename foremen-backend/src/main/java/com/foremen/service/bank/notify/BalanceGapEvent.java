package com.foremen.service.bank.notify;

/**
 * FOR-07-04 (Requirements 7.2, 22.2 row 4; design §Notifications) — a {@code Balance_Gap} was recorded
 * for a bank account. Consumed AFTER_COMMIT by {@link BankNotificationEmitter} (ADMIN + FINANCIER).
 *
 * @param accountId the bank account id (deep-link target)
 * @param gapId     the recorded balance gap id (informational)
 */
public record BalanceGapEvent(Long accountId, Long gapId) {
}
