package com.foremen.service.roomsimport;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.foremen.config.roomsimport.RoomsImportProperties;
import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.model.RoomsImportStatus;
import com.foremen.service.roomsimport.model.DraftRoom;
import com.foremen.service.roomsimport.model.ImportResult;

/**
 * FOR-05-02 (design B2b; Requirements 17.8, 19.6) — the single place that performs terminal job
 * transitions, used by the worker and by the sweep. Each call is a status-conditional update in its own
 * {@code REQUIRES_NEW} transaction and returns the number of affected rows: {@code 1} for the caller
 * that won, {@code 0} when the job is already terminal or gone (cancelled / replaced / lost a race to
 * the other caller). Side effects such as notifications must only follow a result of {@code 1}.
 */
@Component
public class RoomsImportJobTerminator {

    private final RoomsImportJobDao jobDao;
    private final RoomsImportJobMapper mapper;
    private final RoomsImportProperties properties;
    private final RoomsImportNotificationEmitter emitter;

    public RoomsImportJobTerminator(RoomsImportJobDao jobDao, RoomsImportJobMapper mapper,
                                    RoomsImportProperties properties, RoomsImportNotificationEmitter emitter) {
        this.emitter = emitter;
        this.jobDao = jobDao;
        this.mapper = mapper;
        this.properties = properties;
    }

    /**
     * Marks the job {@code COMPLETED} and stores its result.
     *
     * @return affected rows (0 or 1)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int complete(Long jobId, ImportResult result) {
        LocalDateTime now = LocalDateTime.now();
        int rooms = result.rooms() == null ? 0 : result.rooms().size();
        int warnings = countWarnings(result);
        String model = result.modelInfo() == null ? null : result.modelInfo().model();
        int updated = jobDao.terminateIfActive(jobId, RoomsImportStatus.COMPLETED, null, now,
                now.plus(properties.ttl().result()), rooms, warnings, truncate(model, 64),
                RoomsImportJobDao.TERMINAL);
        if (updated == 1) {
            jobDao.findById(jobId).ifPresent(job -> {
                job.setResult(mapper.resultToMap(result));
                jobDao.save(job);
                Long projectId = job.getProject() == null ? null : job.getProject().getId();
                Long creatorId = job.getCreator() == null ? null : job.getCreator().getId();
                afterCommit(() -> emitter.emitCompleted(projectId, creatorId));
            });
        }
        return updated;
    }

    /**
     * Marks the job {@code FAILED} with a stable reason code.
     *
     * @return affected rows (0 or 1)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int fail(Long jobId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        String code = truncate(reason, 64);
        int updated = jobDao.terminateIfActive(jobId, RoomsImportStatus.FAILED, code, now,
                now.plus(properties.ttl().result()), null, null, null, RoomsImportJobDao.TERMINAL);
        if (updated == 1) {
            jobDao.findById(jobId).ifPresent(job -> {
                Long projectId = job.getProject() == null ? null : job.getProject().getId();
                Long creatorId = job.getCreator() == null ? null : job.getCreator().getId();
                afterCommit(() -> emitter.emitFailed(projectId, creatorId, code));
            });
        }
        return updated;
    }

    /** Runs the emission after the terminal update commits (R17.5); immediately if no transaction. */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    static int countWarnings(ImportResult result) {
        int n = result.warnings() == null ? 0 : result.warnings().size();
        if (result.rooms() != null) {
            for (DraftRoom room : result.rooms()) {
                n += room.warnings() == null ? 0 : room.warnings().size();
            }
        }
        return n;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
