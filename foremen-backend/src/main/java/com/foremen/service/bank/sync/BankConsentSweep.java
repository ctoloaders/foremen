package com.foremen.service.bank.sync;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.foremen.service.bank.connection.BankConnectionService;

/**
 * FOR-07-04 (Requirements 1.9, 1.10, 22.2 rows 1–2) — the daily consent sweep
 * ({@code foremen.bank-feed.consent.sweep-cron}, defaults to the {@code "-"} sentinel until configured):
 * <ul>
 *   <li>recomputes {@code ACTIVE} / {@code CONSENT_EXPIRING} / {@code REAUTH_REQUIRED} from the stored
 *       consent expiry. The status transitions publish {@code ConsentExpiringEvent} (the emitter's
 *       throttle sends it once per consent id) and {@code ReauthRequiredEvent} (on the transition only);</li>
 *   <li>removes {@code PENDING_AUTH} connections older than
 *       {@code foremen.bank-feed.consent.pending-auth-max-age} (default 24 h) that have no accounts.</li>
 * </ul>
 */
@Component
public class BankConsentSweep {

    private static final Logger log = LoggerFactory.getLogger(BankConsentSweep.class);

    static final Duration DEFAULT_PENDING_AUTH_MAX_AGE = Duration.ofHours(24);

    private final BankConnectionService connectionService;
    private final Duration pendingAuthMaxAge;

    public BankConsentSweep(BankConnectionService connectionService,
                            @Value("${foremen.bank-feed.consent.pending-auth-max-age:24h}") String pendingAuthMaxAge) {
        this.connectionService = connectionService;
        this.pendingAuthMaxAge = BankSyncOrchestrator.parseDuration(pendingAuthMaxAge, DEFAULT_PENDING_AUTH_MAX_AGE);
    }

    /** One sweep; each part is isolated so a failure in one does not skip the other. */
    @Scheduled(cron = "${foremen.bank-feed.consent.sweep-cron:-}", zone = "${foremen.bank-feed.sync.zone:Europe/Warsaw}")
    public void sweep() {
        try {
            int changed = connectionService.recomputeConsentStatuses();
            if (changed > 0) {
                log.info("Bank consent sweep changed the status of {} connection(s)", changed);
            }
        } catch (RuntimeException e) {
            log.warn("Bank consent recompute failed: {}", e.getMessage());
        }
        try {
            int removed = connectionService.purgeStalePendingAuth(pendingAuthMaxAge);
            if (removed > 0) {
                log.info("Bank consent sweep removed {} abandoned PENDING_AUTH connection(s)", removed);
            }
        } catch (RuntimeException e) {
            log.warn("Bank PENDING_AUTH purge failed: {}", e.getMessage());
        }
    }

    Duration pendingAuthMaxAge() {
        return pendingAuthMaxAge;
    }
}
