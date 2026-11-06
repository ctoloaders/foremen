package com.foremen.service.roomsimport;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.service.roomsimport.classify.RoomTypeResolver.RoomTypeOption;
import com.foremen.service.roomsimport.validate.ExistingRoomRef;

/**
 * FOR-05-02 — read-only data access of the pipeline: the job context, the room type catalogue and the
 * project's existing rooms (read only for duplicate warnings, Requirement 21.3). Returns plain records
 * so the pipeline never touches lazy JPA state.
 */
@Component
public class RoomsImportContextLoader {

    /** Everything the pipeline needs to know about a job. */
    public record JobContext(Long jobId, Long projectId, Long creatorId, String prompt, List<StoredInput> inputs) {
    }

    private final RoomsImportJobDao jobDao;
    private final RoomsImportJobMapper mapper;
    private final RoomTypeDao roomTypeDao;
    private final RoomDao roomDao;

    public RoomsImportContextLoader(RoomsImportJobDao jobDao, RoomsImportJobMapper mapper,
                                    RoomTypeDao roomTypeDao, RoomDao roomDao) {
        this.jobDao = jobDao;
        this.mapper = mapper;
        this.roomTypeDao = roomTypeDao;
        this.roomDao = roomDao;
    }

    @Transactional(readOnly = true)
    public Optional<JobContext> load(Long jobId) {
        return jobDao.findById(jobId).map(job -> new JobContext(job.getId(), job.getProject().getId(),
                job.getCreator().getId(), job.getPromptText(), mapper.readInputs(job)));
    }

    /** Active room types (the catalogue offered to the model and used by the resolver). */
    @Transactional(readOnly = true)
    public List<RoomTypeOption> roomTypes() {
        List<RoomTypeOption> out = new ArrayList<>();
        for (RoomTypeEntity t : roomTypeDao.findAll()) {
            if (t.isActive()) {
                out.add(new RoomTypeOption(t.getId(), t.getCode(), t.getNameRU(), t.getNamePL()));
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<ExistingRoomRef> existingRooms(Long projectId) {
        List<ExistingRoomRef> out = new ArrayList<>();
        for (RoomEntity r : roomDao.findByProjectId(projectId)) {
            out.add(new ExistingRoomRef(r.getId(), r.getLabel(),
                    r.getRoomType() == null ? null : r.getRoomType().getId()));
        }
        return out;
    }
}
