package com.foremen.service.bank.notify;

import java.time.LocalDate;

/**
 * FOR-07-04a (Requirement 12.3) — the Fintable API token is close to expiry. Published by
 * {@code FintableTokenExpiryCheck} after the persisted marker for {@code level} was claimed; consumed
 * AFTER_COMMIT by {@link BankNotificationEmitter}, which notifies ADMIN + FINANCIER.
 *
 * @param level     the threshold reached: {@code 30} (within warn days), {@code 7}, or {@code 0} (expired)
 * @param expiresOn the configured {@code token-expires-on} date
 */
public record FintableTokenExpiringEvent(int level, LocalDate expiresOn) {
}
