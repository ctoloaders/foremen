package com.foremen.service.bank.connection;

import java.time.Duration;
import java.time.Instant;

import com.foremen.dao.model.BankConnectionStatus;
import com.foremen.service.bank.connector.ConnectionStatusHint;

/**
 * FOR-07-04 (Requirements 1.2, 1.9) — pure status rules of a {@code Bank_Connection}.
 *
 * <ul>
 *   <li>{@link #fromHint} maps the connector's {@link ConnectionStatusHint} onto the persisted status
 *       and then applies the consent window ({@link #consentStatus});</li>
 *   <li>{@link #consentStatus} recomputes {@code ACTIVE} / {@code CONSENT_EXPIRING} /
 *       {@code REAUTH_REQUIRED} from {@code consentExpiresAt} and {@code warn-days}: expired
 *       ({@code <= now}) → {@code REAUTH_REQUIRED}; within the window ({@code <= now + warn}) →
 *       {@code CONSENT_EXPIRING}; otherwise {@code ACTIVE}. Statuses outside the consent lifecycle
 *       ({@code PENDING_AUTH}, {@code ERROR}, {@code DISABLED}) are kept, except that an expired
 *       consent always wins over {@code ERROR}.</li>
 * </ul>
 */
public final class ConnectionStatusCalculator {

    private ConnectionStatusCalculator() {
        // Utility class: no instances.
    }

    /**
     * The status to persist for a provider-reported hint.
     *
     * @param current          the persisted status (used when the hint is {@code PENDING_AUTH} or null)
     * @param hint             the connector hint; may be {@code null}
     * @param consentExpiresAt the consent expiry; may be {@code null} (unknown ⇒ no consent window)
     * @param now              the current instant
     * @param warnBefore       the consent warning window ({@code warn-days})
     */
    public static BankConnectionStatus fromHint(BankConnectionStatus current, ConnectionStatusHint hint,
                                                Instant consentExpiresAt, Instant now, Duration warnBefore) {
        if (hint == null || hint == ConnectionStatusHint.PENDING_AUTH) {
            return current;
        }
        return switch (hint) {
            case DISABLED -> BankConnectionStatus.DISABLED;
            case REAUTH_REQUIRED -> BankConnectionStatus.REAUTH_REQUIRED;
            case ERROR -> consentStatus(BankConnectionStatus.ERROR, consentExpiresAt, now, warnBefore);
            case ACTIVE, CONSENT_EXPIRING ->
                    consentStatus(BankConnectionStatus.ACTIVE, consentExpiresAt, now, warnBefore);
            case PENDING_AUTH -> current;
        };
    }

    /**
     * Recomputes the consent-driven status from {@code consentExpiresAt} (sync + daily sweep, R1.9).
     */
    public static BankConnectionStatus consentStatus(BankConnectionStatus current, Instant consentExpiresAt,
                                                     Instant now, Duration warnBefore) {
        if (current == null) {
            return null;
        }
        switch (current) {
            case PENDING_AUTH, DISABLED, REAUTH_REQUIRED:
                return current;
            default:
                break;
        }
        if (consentExpiresAt != null && !consentExpiresAt.isAfter(now)) {
            return BankConnectionStatus.REAUTH_REQUIRED;
        }
        if (current == BankConnectionStatus.ERROR) {
            return current;
        }
        if (consentExpiresAt != null && !consentExpiresAt.isAfter(now.plus(warnBefore))) {
            return BankConnectionStatus.CONSENT_EXPIRING;
        }
        return BankConnectionStatus.ACTIVE;
    }

    /** {@code true} for the statuses in which the connection feeds data ({@code ACTIVE}, {@code CONSENT_EXPIRING}). */
    public static boolean isHealthy(BankConnectionStatus status) {
        return status == BankConnectionStatus.ACTIVE || status == BankConnectionStatus.CONSENT_EXPIRING;
    }
}
