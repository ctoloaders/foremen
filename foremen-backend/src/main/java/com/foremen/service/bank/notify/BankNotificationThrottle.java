package com.foremen.service.bank.notify;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.model.BankConnectionEntity;

/**
 * FOR-07-04 (Requirement 22.2 rows 1 + 3; design §Notifications) — the persisted de-duplication
 * markers behind two bank notifications:
 *
 * <ul>
 *   <li>{@code syncFailed} — at most once per connection per 24 h, via
 *       {@code bank_connections.last_sync_failed_notified_at};</li>
 *   <li>{@code consentExpiring} — once per consent, via
 *       {@code bank_connections.consent_expiring_notified_for}.</li>
 * </ul>
 *
 * <p>Each {@code claim*} method checks the marker and, when the notification is due, <b>stamps it in
 * its own {@link Propagation#REQUIRES_NEW} transaction</b> before returning {@code true}. It is a
 * separate bean so the {@link BankNotificationEmitter}'s {@code AFTER_COMMIT} listeners (which run
 * after the triggering transaction committed) go through the transactional proxy. Claiming before
 * sending means a throttled notification is never sent twice, even if delivery fails afterwards.
 */
@Component
public class BankNotificationThrottle {

    /** The syncFailed throttle window (R22.2 row 3). */
    static final Duration SYNC_FAILED_WINDOW = Duration.ofHours(24);

    /** Width of {@code bank_connections.consent_expiring_notified_for}. */
    private static final int CONSENT_KEY_MAX = 64;

    private final BankConnectionDao connectionDao;

    private Clock clock = Clock.systemUTC();

    public BankNotificationThrottle(BankConnectionDao connectionDao) {
        this.connectionDao = connectionDao;
    }

    /** Test seam for "now". */
    void setClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Claims the syncFailed notification for {@code connectionId}: returns {@code true} (and stamps
     * {@code lastSyncFailedNotifiedAt = now}) when no syncFailed notification was sent in the last
     * 24 h; {@code false} when throttled or the connection no longer exists.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimSyncFailed(Long connectionId) {
        BankConnectionEntity connection = load(connectionId);
        if (connection == null) {
            return false;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime last = connection.getLastSyncFailedNotifiedAt();
        if (last != null && last.plus(SYNC_FAILED_WINDOW).isAfter(now)) {
            return false;
        }
        connection.setLastSyncFailedNotifiedAt(now);
        connectionDao.save(connection);
        return true;
    }

    /**
     * Claims the consentExpiring notification for the consent: returns {@code true} (and stamps
     * {@code consentExpiringNotifiedFor = consentKey}) when this consent was not notified yet;
     * {@code false} when already notified or the connection no longer exists.
     *
     * <p>The consent key is the event's {@code consentId}, else the connection's
     * {@code externalConsentId}, else the consent expiry instant ({@code exp:<instant>}) — so a
     * renewed consent (new id / new expiry) is notified again.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimConsentExpiring(Long connectionId, String consentId) {
        BankConnectionEntity connection = load(connectionId);
        if (connection == null) {
            return false;
        }
        String key = consentKey(connection, consentId);
        if (key.equals(connection.getConsentExpiringNotifiedFor())) {
            return false;
        }
        connection.setConsentExpiringNotifiedFor(key);
        connectionDao.save(connection);
        return true;
    }

    private BankConnectionEntity load(Long connectionId) {
        return connectionId == null ? null : connectionDao.findById(connectionId).orElse(null);
    }

    static String consentKey(BankConnectionEntity connection, String consentId) {
        String key;
        if (consentId != null && !consentId.isBlank()) {
            key = consentId;
        } else if (connection.getExternalConsentId() != null && !connection.getExternalConsentId().isBlank()) {
            key = connection.getExternalConsentId();
        } else if (connection.getConsentExpiresAt() != null) {
            key = "exp:" + connection.getConsentExpiresAt().toInstant();
        } else {
            key = "exp:unknown";
        }
        return key.length() > CONSENT_KEY_MAX ? key.substring(0, CONSENT_KEY_MAX) : key;
    }
}
