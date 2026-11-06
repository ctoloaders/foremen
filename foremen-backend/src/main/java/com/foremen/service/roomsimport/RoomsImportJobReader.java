package com.foremen.service.roomsimport;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.controller.model.RoomsImportJobDto;
import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.model.RoomsImportJobEntity;
import com.foremen.dao.model.RoomsImportStatus;

/**
 * FOR-05-02 (Requirements 2.1, 2.2, 2.6, 9.3) — read side of the import endpoints. Runs the lazy
 * project lookup and the entity → {@link RoomsImportJobDto} mapping inside a read-only transaction so the
 * controller never touches entities.
 */
@Component
public class RoomsImportJobReader {

    /** Draft media ids are generated {@code <uuid>.png}; anything else is rejected before storage is touched. */
    private static final Pattern MEDIA_ID = Pattern.compile("[0-9a-fA-F-]{8,64}\\.[a-z]{3,4}");

    private final RoomsImportJobDao jobDao;
    private final RoomsImportJobMapper mapper;
    private final RoomsImportStorage storage;

    public RoomsImportJobReader(RoomsImportJobDao jobDao, RoomsImportJobMapper mapper,
                                RoomsImportStorage storage) {
        this.jobDao = jobDao;
        this.mapper = mapper;
        this.storage = storage;
    }

    @Transactional(readOnly = true)
    public Optional<RoomsImportJobDto> currentOfProject(Long projectId) {
        return jobDao.findByProjectId(projectId).map(RoomsImportJobReader::toDto);
    }

    @Transactional(readOnly = true)
    public Optional<RoomsImportJobDto> byId(Long jobId) {
        return jobDao.findById(jobId).map(RoomsImportJobReader::toDto);
    }

    /** The owning project of the job, or empty when the job is gone. */
    @Transactional(readOnly = true)
    public Optional<Long> projectIdOf(Long jobId) {
        return jobDao.findById(jobId).map(j -> j.getProject().getId());
    }

    /**
     * Bytes of a draft media object. Only ids listed in the job's result are addressable; the storage
     * key is rebuilt from the job id and the id, never taken from the client.
     *
     * @return empty when the id is malformed, not listed, or the object is gone
     */
    @Transactional(readOnly = true)
    public Optional<byte[]> draftMedia(Long jobId, String mediaId) {
        if (mediaId == null || !MEDIA_ID.matcher(mediaId).matches()) {
            return Optional.empty();
        }
        Optional<RoomsImportJobEntity> job = jobDao.findById(jobId);
        if (job.isEmpty()) {
            return Optional.empty();
        }
        Set<String> keys = mapper.objectKeys(job.get());
        String key = RoomsImportJobMapper.mediaKey(jobId, mediaId);
        if (!keys.contains(key)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(storage.read(key));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    static RoomsImportJobDto toDto(RoomsImportJobEntity job) {
        RoomsImportStatus status = job.getStatus();
        boolean completed = status == RoomsImportStatus.COMPLETED;
        return new RoomsImportJobDto(
                job.getId(),
                job.getProject().getId(),
                status,
                status,
                status == RoomsImportStatus.FAILED ? job.getFailureReason() : null,
                job.getCreatedDate() != null ? job.getCreatedDate() : job.getStartedAt(),
                job.getCompletedAt(),
                job.getAppliedAt(),
                completed ? job.getResult() : null);
    }
}
