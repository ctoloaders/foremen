package com.foremen.service.bank.notify;

/**
 * FOR-07-04 (Requirement 22.2 row 1; design §Notifications) — a bank connection entered
 * {@code CONSENT_EXPIRING}. Published by the consent sweep / sync orchestrator; consumed AFTER_COMMIT
 * by {@link BankNotificationEmitter}, which notifies ADMIN + FINANCIER at most <b>once per consent</b>
 * (marker {@code bank_connections.consent_expiring_notified_for}).
 *
 * @param connectionId the bank connection id (deep-link target)
 * @param consentId    the provider consent id that is expiring; {@code null} falls back to the
 *                     connection's stored consent id / expiry when deriving the once-per-consent key
 */
public record ConsentExpiringEvent(Long connectionId, String consentId) {
}
