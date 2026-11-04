package com.foremen.service.bank.notify;

/**
 * FOR-07-04 (Requirement 22.2 row 2; design §Notifications) — a bank connection entered
 * {@code REAUTH_REQUIRED}. The publisher emits it only on the transition into that status; consumed
 * AFTER_COMMIT by {@link BankNotificationEmitter} (ADMIN + FINANCIER).
 *
 * @param connectionId the bank connection id (deep-link target)
 */
public record ReauthRequiredEvent(Long connectionId) {
}
