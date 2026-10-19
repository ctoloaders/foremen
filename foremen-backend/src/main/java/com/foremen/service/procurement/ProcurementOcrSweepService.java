package com.foremen.service.procurement;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.foremen.dao.ProcurementDocumentDao;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-06-03 Amendment A1 (R18.4) — the crash-recovery sweep. If the backend restarts while a
 * document is in a non-terminal processing stage ({@code QUEUED/RASTERIZING/.../MATCHING}), the
 * in-flight async work is lost; without recovery the document would rest forever in that stage. This
 * sweep finds every document stuck in a non-terminal stage past a staleness window and
 * <b>re-enqueues</b> it by re-publishing {@link ProcurementOcrRequested}, so the worker re-runs the
 * pipeline from the stored raw bytes. Re-enqueue is idempotent (the worker replaces lines + renditions
 * atomically) and the user-facing retry action covers anything the sweep misses.
 *
 * <p>Runs on two triggers:
 * <ul>
 *   <li><b>At startup</b> ({@link ApplicationReadyEvent}) — recover anything the previous process
 *       left mid-flight.</li>
 *   <li><b>Periodically</b> ({@code @Scheduled}, cron {@code foremen.procurement.ocr-sweep.cron},
 *       default {@code "-"} = disabled until configured, mirroring {@code OverdueSweepService} /
 *       {@code ImageReconciliationJob}) — catch stragglers on a long-running process.</li>
 * </ul>
 *
 * <p>The staleness window ({@code foremen.procurement.ocr-sweep.staleness}, default 10m) avoids
 * re-enqueuing a document that is legitimately still being processed right now (its {@code
 * updatedDate} was just bumped by a stage commit). On a single VM a plain {@code @Scheduled} is
 * sufficient; if this ever runs multi-node, wrap {@link #sweep()} in a ShedLock {@code @SchedulerLock}.
 */
@Slf4j
@Service
public class ProcurementOcrSweepService {

    private final ProcurementDocumentDao documentDao;
    private final ApplicationEventPublisher eventPublisher;
    private final Duration staleness;

    public ProcurementOcrSweepService(
            ProcurementDocumentDao documentDao,
            ApplicationEventPublisher eventPublisher,
            @Value("${foremen.procurement.ocr-sweep.staleness:PT10M}") Duration staleness) {
        this.documentDao = documentDao;
        this.eventPublisher = eventPublisher;
        this.staleness = staleness == null || staleness.isZero() || staleness.isNegative()
                ? Duration.ofMinutes(10)
                : staleness;
    }

    /** Startup recovery pass (A1 R18.4): re-enqueue anything the previous process left mid-flight. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        sweep();
    }

    /**
     * The periodic sweep (A1 R18.4). Finds documents stuck in a non-terminal stage past the staleness
     * window and re-enqueues each by re-publishing {@link ProcurementOcrRequested}. Disabled by
     * default via the {@code "-"} cron sentinel until an operator configures a schedule.
     */
    @Scheduled(cron = "${foremen.procurement.ocr-sweep.cron:-}")
    public void sweep() {
        LocalDateTime cutoff = LocalDateTime.now().minus(staleness);
        List<Long> stale;
        try {
            stale = documentDao.findStaleProcessingIds(cutoff);
        } catch (RuntimeException e) {
            log.warn("Procurement OCR sweep query failed (skipping this run): {}", e.getMessage());
            return;
        }
        if (stale.isEmpty()) {
            return;
        }
        log.info("Procurement OCR sweep: re-enqueuing {} stale document(s) stuck in a processing stage",
                stale.size());
        for (Long id : stale) {
            try {
                eventPublisher.publishEvent(new ProcurementOcrRequested(id));
            } catch (RuntimeException e) {
                log.warn("Failed to re-enqueue stale procurement document {} (continuing): {}",
                        id, e.getMessage());
            }
        }
    }
}
