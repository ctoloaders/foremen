package com.foremen.service.roomsimport;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomsImportJobEntity;
import com.foremen.dao.model.RoomsImportStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.roomsimport.ai.RoomsVisionClient;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;

/**
 * FOR-05-02 (Requirements 1.3, 1.8, 2.3-2.6, 19, 24; design B2a) — job creation, cancel / discard and
 * the current-job lookup.
 *
 * <p><b>Single-job rule.</b> {@code rooms_import_jobs} has {@code UNIQUE (project_id)}. {@link #start}
 * runs in one transaction that locks the project's job row: an active job yields
 * {@code 429 error.roomsimport.in_progress}; a terminal job is deleted (row now, stored objects after
 * commit) and replaced; a unique-violation race between two first starts is mapped to the same
 * {@code 429}, never a {@code 500}.
 *
 * <p>Callers (the controller, task 4.2) are responsible for request validation (types, counts, sizes)
 * and project-access checks; this service enforces the feature switch, the storage availability and the
 * single-job rule.
 */
@Slf4j
@Service
public class RoomsImportService {

    /** Result of a successful start: the new job id and its (QUEUED) status. */
    public record StartResult(Long jobId, RoomsImportStatus status) {
    }

    private final RoomsImportJobDao jobDao;
    private final RoomsImportJobMapper mapper;
    private final RoomsImportStorage storage;
    private final RoomsVisionClient visionClient;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    public RoomsImportService(RoomsImportJobDao jobDao, RoomsImportJobMapper mapper, RoomsImportStorage storage,
                              RoomsVisionClient visionClient, ApplicationEventPublisher eventPublisher,
                              PlatformTransactionManager transactionManager) {
        this.jobDao = jobDao;
        this.mapper = mapper;
        this.storage = storage;
        this.visionClient = visionClient;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Creates a {@code QUEUED} job for the project, stores its inputs and publishes the after-commit
     * event.
     *
     * @param projectId   the project (access already verified by the caller)
     * @param createdById the starting user
     * @param prompt      free-text prompt, may be blank when files are given
     * @param files       validated uploads, may be empty when a prompt is given
     * @throws ForemenApiException 503 when the feature / storage is unavailable, 429 when a job is active
     */
    public StartResult start(Long projectId, Long createdById, String prompt, List<MultipartFile> files) {
        if (!visionClient.isEnabled() || !storage.isConfigured()) {
            throw new ForemenApiException(HttpStatus.SERVICE_UNAVAILABLE, RoomsImportErrors.UNAVAILABLE);
        }
        List<MultipartFile> uploads = files == null ? List.of() : files;
        try {
            return transactionTemplate.execute(status -> doStart(projectId, createdById, prompt, uploads));
        } catch (DataIntegrityViolationException race) {
            // Two concurrent first starts: FOR UPDATE found no row to lock, the unique index decided.
            Optional<RoomsImportJobEntity> winner = findCurrent(projectId);
            if (winner.isPresent()) {
                RoomsImportJobEntity j = winner.get();
                throw new RoomsImportInProgressException(j.getId(), j.getStatus(), j.getStartedAt());
            }
            throw race;
        }
    }

    private StartResult doStart(Long projectId, Long createdById, String prompt, List<MultipartFile> files) {
        Optional<RoomsImportJobEntity> existing = jobDao.findByProjectIdForUpdate(projectId);
        if (existing.isPresent()) {
            RoomsImportJobEntity current = existing.get();
            if (!current.getStatus().isTerminal()) {
                throw new RoomsImportInProgressException(current.getId(), current.getStatus(),
                        current.getStartedAt());
            }
            // Terminal job: replace it. The delete must hit the database before the insert, otherwise
            // the unique index on project_id would reject the new row.
            Set<String> oldKeys = mapper.objectKeys(current);
            Long oldId = current.getId();
            jobDao.deleteByJobId(oldId);
            storage.deleteJobObjectsAfterCommit(oldId, oldKeys);
        }

        RoomsImportJobEntity job = new RoomsImportJobEntity();
        job.setProject(entityManager.getReference(ProjectEntity.class, projectId));
        job.setCreator(entityManager.getReference(UserEntity.class, createdById));
        job.setStatus(RoomsImportStatus.QUEUED);
        job.setPromptText(prompt == null || prompt.isBlank() ? null : prompt);
        job.setInputs(new ArrayList<>());
        job.setStartedAt(LocalDateTime.now());
        job = jobDao.save(job);
        entityManager.flush(); // a unique violation surfaces here, before anything is stored

        List<StoredInput> stored = new ArrayList<>();
        try {
            for (MultipartFile file : files) {
                String key = storage.storeInput(job.getId(), file);
                stored.add(new StoredInput(sanitizeFileName(file.getOriginalFilename()), file.getContentType(),
                        file.getSize(), key, null));
            }
        } catch (RuntimeException e) {
            storage.deleteNow(stored.stream().map(StoredInput::objectKey).toList());
            throw e;
        }
        job.setInputs(mapper.inputsToMaps(stored));
        jobDao.save(job);
        if (!stored.isEmpty()) {
            registerRollbackCleanup(stored);
        }
        eventPublisher.publishEvent(new RoomsImportRequested(job.getId()));
        return new StartResult(job.getId(), job.getStatus());
    }

    /** The transaction may still roll back after the objects are stored: remove them in that case. */
    private void registerRollbackCleanup(List<StoredInput> stored) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    storage.deleteNow(stored.stream().map(StoredInput::objectKey).toList());
                }
            }
        });
    }

    /**
     * Cancels an <b>active</b> job (conditional delete). Stored objects are removed after commit.
     *
     * @throws ForemenApiException 404 when the job is gone, 409 {@code not_cancellable} when terminal
     */
    @Transactional
    public void cancel(Long jobId) {
        RoomsImportJobEntity job = jobDao.findById(jobId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, RoomsImportErrors.NOT_FOUND, jobId));
        Set<String> keys = mapper.objectKeys(job);
        if (jobDao.deleteIfActive(jobId, RoomsImportJobDao.TERMINAL) == 0) {
            // Either it just reached a terminal state or it vanished in the meantime.
            if (jobDao.existsById(jobId)) {
                throw new ForemenApiException(HttpStatus.CONFLICT, RoomsImportErrors.NOT_CANCELLABLE, jobId);
            }
            throw new ForemenApiException(HttpStatus.NOT_FOUND, RoomsImportErrors.NOT_FOUND, jobId);
        }
        storage.deleteJobObjectsAfterCommit(jobId, keys);
        log.debug("Rooms import job {} cancelled", jobId);
    }

    /** Discards a job of any status (also cancels an active one). Idempotent. */
    @Transactional
    public void discard(Long jobId) {
        Optional<RoomsImportJobEntity> job = jobDao.findById(jobId);
        if (job.isEmpty()) {
            return;
        }
        Set<String> keys = mapper.objectKeys(job.get());
        if (jobDao.deleteByJobId(jobId) > 0) {
            storage.deleteJobObjectsAfterCommit(jobId, keys);
        }
    }

    /** The project's single job row, if any. */
    @Transactional(readOnly = true)
    public Optional<RoomsImportJobEntity> findCurrent(Long projectId) {
        return jobDao.findByProjectId(projectId);
    }

    @Transactional(readOnly = true)
    public Optional<RoomsImportJobEntity> findById(Long jobId) {
        return jobDao.findById(jobId);
    }

    /** Client file names are metadata only: strip any path and control characters, bound the length. */
    static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "file";
        }
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (base.isEmpty()) {
            return "file";
        }
        return base.length() > 200 ? base.substring(base.length() - 200) : base;
    }
}
