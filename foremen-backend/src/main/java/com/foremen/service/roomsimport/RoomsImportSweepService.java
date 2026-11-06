package com.foremen.service.roomsimport;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.foremen.config.roomsimport.RoomsImportProperties;
import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.model.RoomsImportJobEntity;
import com.foremen.service.image.ImageStorage;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-05-02 (Requirements 2.4, 16.3, 19.6, 24.7) — the cleanup and recovery sweep.
 *
 * <ol>
 *   <li><b>Stuck jobs</b> — an active job whose last update is older than {@code ttl.stuck-timeout} is
 *       failed with {@code error.roomsimport.failed.timeout} through
 *       {@link RoomsImportJobTerminator#fail}, so a crashed worker or a restart never blocks a project
 *       (the status-conditional update makes a worker/sweep race notify at most once).</li>
 *   <li><b>TTL cleanup</b> — terminal jobs past {@code expires_at} are deleted together with their
 *       stored inputs and draft media.</li>
 *   <li><b>Orphan objects</b> — objects under {@code rooms-import/{jobId}/} whose job row no longer
 *       exists (cancelled jobs that wrote late, replaced jobs) and that are older than the stuck
 *       timeout are deleted.</li>
 * </ol>
 *
 * <p>Runs at startup (restart recovery) and on a schedule
 * ({@code foremen.rooms-import.sweep.cron}, default every five minutes). Single-VM deployment: a plain
 * {@code @Scheduled} suffices, like {@code ProcurementOcrSweepService}.
 */
@Slf4j
@Service
public class RoomsImportSweepService {

    private static final Pattern JOB_PREFIX = Pattern.compile("^rooms-import/(\\d+)/.*");

    private final RoomsImportJobDao jobDao;
    private final RoomsImportJobMapper mapper;
    private final RoomsImportJobTerminator terminator;
    private final RoomsImportStorage storage;
    private final RoomsImportProperties properties;

    public RoomsImportSweepService(RoomsImportJobDao jobDao, RoomsImportJobMapper mapper,
                                   RoomsImportJobTerminator terminator, RoomsImportStorage storage,
                                   RoomsImportProperties properties) {
        this.jobDao = jobDao;
        this.mapper = mapper;
        this.terminator = terminator;
        this.storage = storage;
        this.properties = properties;
    }

    /** Restart recovery: fail whatever the previous process left running beyond the timeout. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        sweep();
    }

    @Scheduled(cron = "${foremen.rooms-import.sweep.cron:0 */5 * * * *}")
    public void sweep() {
        run("stuck jobs", this::failStuckJobs);
        run("expired jobs", this::deleteExpiredJobs);
        run("orphan objects", this::deleteOrphanObjects);
    }

    private void run(String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.warn("Rooms import sweep ({}) failed, skipping this run: {}", what, e.getMessage());
        }
    }

    /** @return the number of jobs failed by this pass */
    int failStuckJobs() {
        Duration timeout = properties.ttl().stuckTimeout();
        List<Long> stuck = jobDao.findStuckIds(LocalDateTime.now().minus(timeout), RoomsImportJobDao.TERMINAL);
        int failed = 0;
        for (Long id : stuck) {
            try {
                if (terminator.fail(id, RoomsImportErrors.FAILED_TIMEOUT) == 1) {
                    failed++;
                }
            } catch (RuntimeException e) {
                log.warn("Failed to time out rooms import job {} (continuing): {}", id, e.getMessage());
            }
        }
        if (failed > 0) {
            log.info("Rooms import sweep: failed {} stuck job(s)", failed);
        }
        return failed;
    }

    /** @return the number of jobs removed by this pass */
    int deleteExpiredJobs() {
        List<Long> expired = jobDao.findExpiredIds(LocalDateTime.now(), RoomsImportJobDao.TERMINAL);
        int deleted = 0;
        for (Long id : expired) {
            try {
                RoomsImportJobEntity job = jobDao.findById(id).orElse(null);
                var keys = job == null ? java.util.Set.<String>of() : mapper.objectKeys(job);
                if (jobDao.deleteByJobId(id) > 0) {
                    deleted++;
                }
                storage.deleteNow(keys);
                storage.deleteJobPrefix(id);
            } catch (RuntimeException e) {
                log.warn("Failed to clean up expired rooms import job {} (continuing): {}", id, e.getMessage());
            }
        }
        if (deleted > 0) {
            log.info("Rooms import sweep: removed {} expired job(s)", deleted);
        }
        return deleted;
    }

    /** @return the number of orphan objects deleted */
    int deleteOrphanObjects() {
        Instant now = Instant.now();
        Duration minAge = properties.ttl().stuckTimeout();
        Map<Long, Boolean> jobExists = new LinkedHashMap<>();
        int deleted = 0;
        for (ImageStorage.StoredObject object : storage.listAll()) {
            Matcher m = JOB_PREFIX.matcher(object.key());
            if (!m.matches()) {
                continue;
            }
            Long jobId = Long.valueOf(m.group(1));
            boolean exists = jobExists.computeIfAbsent(jobId, jobDao::existsById);
            // Young objects may belong to a job that is being created (row not yet committed).
            if (!exists && RoomsImportStorage.olderThan(object, minAge, now)) {
                storage.deleteNow(List.of(object.key()));
                deleted++;
            }
        }
        if (deleted > 0) {
            log.info("Rooms import sweep: deleted {} orphan object(s)", deleted);
        }
        return deleted;
    }
}
