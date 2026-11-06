package com.foremen.service.roomsimport;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomsImportJobEntity;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-05-02 (Requirements 22.2, 22.3, 21.4) — derives the read-only {@code aiImported} marker of a
 * freshly created room from a <b>verified</b> {@code importJobId}.
 *
 * <p>The job must exist and belong to the room's project. On success the room gets
 * {@code aiImported = true} and the job's {@code applied_at} is set the first time. On any failure
 * (unknown, foreign or expired job) {@link #link} returns {@code false}, the room stays
 * {@code aiImported = false} and saving continues — it never throws.
 *
 * <p>This is the only writer of the marker; it is create-only and never updates or deletes any other
 * room, so an import stays append-only.
 */
@Slf4j
@Service
public class RoomImportLinkService {

    private final RoomsImportJobDao jobDao;
    private final RoomDao roomDao;

    public RoomImportLinkService(RoomsImportJobDao jobDao, RoomDao roomDao) {
        this.jobDao = jobDao;
        this.roomDao = roomDao;
    }

    /**
     * @param roomId      the room created in this save
     * @param importJobId the claimed import job (may be {@code null})
     * @return {@code true} when the job was verified and the marker set; {@code false} when the
     *         reference failed verification (the room stays unmarked)
     */
    public boolean link(Long roomId, Long importJobId) {
        try {
            RoomEntity room = roomDao.findById(roomId).orElse(null);
            RoomsImportJobEntity job = importJobId == null ? null : jobDao.findById(importJobId).orElse(null);
            if (room == null || job == null || room.getProject() == null || job.getProject() == null
                    || !room.getProject().getId().equals(job.getProject().getId())) {
                return false;
            }
            room.setAiImported(true);
            roomDao.save(room);
            if (job.getAppliedAt() == null) {
                job.setAppliedAt(LocalDateTime.now());
                jobDao.save(job);
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("Import job {} could not be linked to room {}: {}", importJobId, roomId, e.getMessage());
            return false;
        }
    }
}
