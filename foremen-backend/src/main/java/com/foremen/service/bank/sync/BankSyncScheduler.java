package com.foremen.service.bank.sync;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankConnectionStatus;
import com.foremen.dao.model.SyncTrigger;

/**
 * FOR-07-04 (Requirements 5.1, 5.6, 9.6) — the scheduled fallback pull.
 *
 * <p>Each tick ({@code foremen.bank-feed.sync.cron}, zone {@code foremen.bank-feed.sync.zone}, default
 * Europe/Warsaw) first recovers stale runs, then enqueues a {@link SyncTrigger#SCHEDULE} run for every
 * {@code ACTIVE} / {@code CONSENT_EXPIRING} / {@code ERROR} connection. The pull only reads data the
 * provider already holds — no (background) refresh is requested (R9.6). The cron defaults to the
 * {@code "-"} sentinel (registered, never fires) until configured, like the other sweeps; Spring
 * scheduling is enabled application-wide by {@code ImageStorageConfig}.
 *
 * <p>Start-up ({@link ApplicationReadyEvent}): stale-run recovery and resubmission of queued runs.
 */
@Component
public class BankSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(BankSyncScheduler.class);

    static final List<BankConnectionStatus> SYNCABLE = List.of(
            BankConnectionStatus.ACTIVE, BankConnectionStatus.CONSENT_EXPIRING, BankConnectionStatus.ERROR);

    private final BankSyncOrchestrator orchestrator;
    private final BankConnectionDao connectionDao;

    public BankSyncScheduler(BankSyncOrchestrator orchestrator, BankConnectionDao connectionDao) {
        this.orchestrator = orchestrator;
        this.connectionDao = connectionDao;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            orchestrator.recoverOnStartup();
        } catch (RuntimeException e) {
            log.warn("Bank sync start-up recovery failed: {}", e.getMessage());
        }
    }

    /**
     * The scheduled tick.
     *
     * @return the number of connections a run was requested for
     */
    @Scheduled(cron = "${foremen.bank-feed.sync.cron:-}", zone = "${foremen.bank-feed.sync.zone:Europe/Warsaw}")
    public int tick() {
        try {
            orchestrator.recoverStale();
        } catch (RuntimeException e) {
            log.warn("Bank sync stale-run recovery failed: {}", e.getMessage());
        }
        List<BankConnectionEntity> connections;
        try {
            connections = connectionDao.findByStatusInOrderByIdAsc(SYNCABLE);
        } catch (RuntimeException e) {
            log.warn("Bank sync scheduler query failed (skipping this tick): {}", e.getMessage());
            return 0;
        }
        int requested = 0;
        for (BankConnectionEntity connection : connections) {
            try {
                if (orchestrator.enqueue(connection.getId(), SyncTrigger.SCHEDULE, BankSyncOrchestrator.SYSTEM)
                        .isPresent()) {
                    requested++;
                }
            } catch (RuntimeException e) {
                log.warn("Scheduled bank sync for connection {} not requested: {}", connection.getId(), e.getMessage());
            }
        }
        if (requested > 0) {
            log.info("Bank sync scheduler requested {} run(s)", requested);
        }
        return requested;
    }
}
