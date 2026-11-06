package com.foremen.service.roomsimport;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.model.RoomsImportStatus;

/**
 * FOR-05-02 (Requirements 2.3, 16.1, 24.3) — commits each stage transition in its own
 * {@code REQUIRES_NEW} transaction so a poller observes progress (same technique as
 * {@code ProcurementOcrWorker.markStage}). A transition that updates 0 rows means the job was
 * cancelled / replaced (or already failed by the sweep) and surfaces as {@link JobCancelledException}.
 */
@Component
public class RoomsImportStageMarker {

    private final RoomsImportJobDao jobDao;

    public RoomsImportStageMarker(RoomsImportJobDao jobDao) {
        this.jobDao = jobDao;
    }

    /**
     * @throws JobCancelledException when 0 rows were updated
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStage(Long jobId, RoomsImportStatus stage) {
        LocalDateTime now = LocalDateTime.now();
        int updated = jobDao.updateStatusIfActive(jobId, stage, now, RoomsImportJobDao.TERMINAL);
        if (updated == 0) {
            throw new JobCancelledException(jobId);
        }
        jobDao.findById(jobId).ifPresent(job -> {
            List<Map<String, Object>> stageLog = job.getStageLog() == null
                    ? new ArrayList<>() : new ArrayList<>(job.getStageLog());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("stage", stage.name());
            entry.put("at", now.toString());
            stageLog.add(entry);
            job.setStageLog(stageLog);
            jobDao.save(job);
        });
    }

    /**
     * Bumps the job's last-update time so a long measuring stage is not mistaken for a stuck job.
     *
     * @return {@code false} when the job is gone or terminal
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean heartbeat(Long jobId) {
        return jobDao.touchIfActive(jobId, LocalDateTime.now(), RoomsImportJobDao.TERMINAL) > 0;
    }
}
