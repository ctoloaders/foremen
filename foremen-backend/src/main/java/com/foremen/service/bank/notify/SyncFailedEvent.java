package com.foremen.service.bank.notify;

/**
 * FOR-07-04 (Requirement 22.2 row 3; design §Notifications) — a {@code Sync_Run} ended {@code FAILED}.
 * The publisher emits it on every failure; {@link BankNotificationEmitter} throttles delivery to at
 * most once per connection per 24 h (marker {@code bank_connections.last_sync_failed_notified_at}).
 *
 * @param connectionId the bank connection id (deep-link target + throttle key)
 * @param runId        the failed sync run id (informational)
 */
public record SyncFailedEvent(Long connectionId, Long runId) {
}
