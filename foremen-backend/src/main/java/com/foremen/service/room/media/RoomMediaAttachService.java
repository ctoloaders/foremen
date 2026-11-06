package com.foremen.service.room.media;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomMediaDao;
import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomMediaEntity;
import com.foremen.dao.model.RoomMediaKind;
import com.foremen.dao.model.RoomsImportJobEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.document.pdf.InMemoryMultipartFile;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.roomsimport.RoomsImportJobMapper;
import com.foremen.service.roomsimport.RoomsImportStorage;
import com.foremen.service.roomsimport.model.DraftMedia;
import com.foremen.service.roomsimport.model.DraftRoom;
import com.foremen.service.roomsimport.model.ImportMediaRef;
import com.foremen.service.roomsimport.model.ImportResult;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-05-02 (Requirements 9.4, 9.5, 9.7) — attaches draft import media to a freshly created room.
 *
 * <p>For every {@link ImportMediaRef} the job must exist, belong to the <b>same project</b> as the room
 * and list the media id in its result; the bytes are then copied from the job's draft namespace
 * ({@code rooms-import/{jobId}/media/}) into the room's permanent namespace ({@code rooms/{roomId}/})
 * and a {@code SOURCE_CROP} {@link RoomMediaEntity} row is created. The draft stays until the job is
 * discarded or swept.
 *
 * <p><b>Soft failure.</b> A reference that is malformed, foreign, expired, or whose object is gone or
 * cannot be copied is returned in the skipped list; nothing is thrown, so the surrounding bulk save
 * (and the room creation) is never affected.
 */
@Slf4j
@Service
public class RoomMediaAttachService {

    /** Object-storage namespace prefix of attached import media: {@code rooms/{roomId}}. */
    static final String NAMESPACE_PREFIX = "rooms/";
    private static final String DEFAULT_CONTENT_TYPE = "image/png";

    private final RoomsImportJobDao jobDao;
    private final RoomsImportJobMapper jobMapper;
    private final RoomsImportStorage draftStorage;
    private final ImageStorage objectStorage;
    private final RoomMediaDao mediaDao;
    private final RoomDao roomDao;
    private final UserDao userDao;

    public RoomMediaAttachService(RoomsImportJobDao jobDao, RoomsImportJobMapper jobMapper,
                                  RoomsImportStorage draftStorage, ImageStorage objectStorage,
                                  RoomMediaDao mediaDao, RoomDao roomDao, UserDao userDao) {
        this.jobDao = jobDao;
        this.jobMapper = jobMapper;
        this.draftStorage = draftStorage;
        this.objectStorage = objectStorage;
        this.mediaDao = mediaDao;
        this.roomDao = roomDao;
        this.userDao = userDao;
    }

    /**
     * Attaches the referenced draft media to the room.
     *
     * @param roomId the created room
     * @param refs   the requested references (may be {@code null} or empty)
     * @return the references that could not be attached (empty when all succeeded); never {@code null}
     */
    public List<ImportMediaRef> attach(Long roomId, List<ImportMediaRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        List<ImportMediaRef> skipped = new ArrayList<>();
        RoomEntity room = roomDao.findById(roomId).orElse(null);
        UserEntity uploader = currentUser();
        if (room == null || room.getProject() == null || uploader == null) {
            skipped.addAll(refs.stream().filter(r -> r != null).toList());
            return skipped;
        }
        Long projectId = room.getProject().getId();
        Set<ImportMediaRef> seen = new LinkedHashSet<>();
        for (ImportMediaRef ref : refs) {
            if (ref == null || !seen.add(ref)) {
                continue; // null entries and exact duplicates are ignored, not attached twice
            }
            try {
                if (!attachOne(room, projectId, uploader, ref)) {
                    skipped.add(ref);
                }
            } catch (RuntimeException e) {
                log.warn("Import media {} of job {} could not be attached to room {}: {}",
                        ref.mediaId(), ref.jobId(), roomId, e.getMessage());
                skipped.add(ref);
            }
        }
        return skipped;
    }

    private boolean attachOne(RoomEntity room, Long projectId, UserEntity uploader, ImportMediaRef ref) {
        if (ref.jobId() == null || ref.mediaId() == null || ref.mediaId().isBlank()) {
            return false;
        }
        Optional<RoomsImportJobEntity> jobOpt = jobDao.findById(ref.jobId());
        if (jobOpt.isEmpty()) {
            return false;
        }
        RoomsImportJobEntity job = jobOpt.get();
        if (job.getProject() == null || !projectId.equals(job.getProject().getId())) {
            return false; // media isolation: only a job of the same project may feed the room
        }
        String key = RoomsImportJobMapper.mediaKey(job.getId(), ref.mediaId());
        if (!jobMapper.objectKeys(job).contains(key)) {
            return false; // only ids listed in the job result are addressable
        }
        byte[] bytes = draftStorage.read(key);
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        String contentType = contentTypeOf(job, ref.mediaId());
        String storageUri = objectStorage.storeObject(
                new InMemoryMultipartFile("media", ref.mediaId(), contentType, bytes),
                NAMESPACE_PREFIX + room.getId());

        RoomMediaEntity media = new RoomMediaEntity();
        media.setRoom(room);
        media.setFileName(ref.mediaId());
        media.setContentType(contentType);
        media.setSizeBytes((long) bytes.length);
        media.setStorageUri(storageUri);
        media.setKind(RoomMediaKind.SOURCE_CROP);
        media.setUploadedBy(uploader);
        mediaDao.save(media);
        return true;
    }

    private String contentTypeOf(RoomsImportJobEntity job, String mediaId) {
        ImportResult result = jobMapper.readResult(job);
        if (result != null && result.rooms() != null) {
            for (DraftRoom room : result.rooms()) {
                if (room.media() == null) {
                    continue;
                }
                for (DraftMedia m : room.media()) {
                    if (mediaId.equals(m.mediaId()) && m.contentType() != null && !m.contentType().isBlank()) {
                        return m.contentType();
                    }
                }
            }
        }
        return DEFAULT_CONTENT_TYPE;
    }

    private UserEntity currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            return null;
        }
        try {
            return userDao.findById(Long.valueOf(auth.getName().trim())).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
