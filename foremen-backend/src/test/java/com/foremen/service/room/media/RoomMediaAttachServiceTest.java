package com.foremen.service.room.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomMediaDao;
import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomMediaEntity;
import com.foremen.dao.model.RoomMediaKind;
import com.foremen.dao.model.RoomsImportJobEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.roomsimport.RoomsImportJobMapper;
import com.foremen.service.roomsimport.RoomsImportStorage;
import com.foremen.service.roomsimport.model.DraftMedia;
import com.foremen.service.roomsimport.model.DraftRoom;
import com.foremen.service.roomsimport.model.ImportMediaRef;
import com.foremen.service.roomsimport.model.ImportResult;

/**
 * FOR-05-02 (Requirements 9.4, 9.5, 9.7): same-project check, copy to {@code rooms/{roomId}} and
 * soft failure of {@link RoomMediaAttachService}.
 */
class RoomMediaAttachServiceTest {

    private static final Long PROJECT = 5L;
    private static final Long ROOM = 70L;
    private static final Long JOB = 9L;
    private static final String MEDIA = "11111111-2222-3333-4444-555555555555.png";
    private static final byte[] BYTES = {1, 2, 3, 4};

    private final RoomsImportJobDao jobDao = mock(RoomsImportJobDao.class);
    private final RoomsImportJobMapper jobMapper = new RoomsImportJobMapper(new ObjectMapper());
    private final ImageStorage imageStorage = mock(ImageStorage.class);
    private final RoomsImportStorage draftStorage = new RoomsImportStorage(imageStorage);
    private final RoomMediaDao mediaDao = mock(RoomMediaDao.class);
    private final RoomDao roomDao = mock(RoomDao.class);
    private final UserDao userDao = mock(UserDao.class);
    private final RoomMediaAttachService service = new RoomMediaAttachService(
            jobDao, jobMapper, draftStorage, imageStorage, mediaDao, roomDao, userDao);

    @BeforeEach
    void setUp() {
        RoomEntity room = new RoomEntity();
        room.setId(ROOM);
        room.setProject(project(PROJECT));
        when(roomDao.findById(ROOM)).thenReturn(Optional.of(room));
        when(userDao.findById(3L)).thenReturn(Optional.of(new UserEntity()));
        when(jobDao.findById(JOB)).thenReturn(Optional.of(job(JOB, PROJECT, MEDIA)));
        when(imageStorage.readObject(RoomsImportJobMapper.mediaKey(JOB, MEDIA))).thenReturn(BYTES);
        when(imageStorage.storeObject(any(), anyString())).thenReturn("rooms/70/stored.png");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("3", "n", List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void attachCopiesDraftIntoRoomNamespaceAndCreatesSourceCropRow() {
        List<ImportMediaRef> skipped = service.attach(ROOM, List.of(new ImportMediaRef(JOB, MEDIA)));

        assertThat(skipped).isEmpty();
        ArgumentCaptor<MultipartFile> file = ArgumentCaptor.forClass(MultipartFile.class);
        verify(imageStorage).storeObject(file.capture(), org.mockito.ArgumentMatchers.eq("rooms/70"));
        assertThat(file.getValue().getContentType()).isEqualTo("image/png");
        ArgumentCaptor<RoomMediaEntity> row = ArgumentCaptor.forClass(RoomMediaEntity.class);
        verify(mediaDao).save(row.capture());
        assertThat(row.getValue().getKind()).isEqualTo(RoomMediaKind.SOURCE_CROP);
        assertThat(row.getValue().getStorageUri()).isEqualTo("rooms/70/stored.png");
        assertThat(row.getValue().getSizeBytes()).isEqualTo(BYTES.length);
        assertThat(row.getValue().getRoom().getId()).isEqualTo(ROOM);
        // the draft is kept (only discard / sweep removes it)
        verify(imageStorage, never()).deleteIfOrphan(anyString());
    }

    @Test
    void jobOfAnotherProjectIsSkipped() {
        when(jobDao.findById(JOB)).thenReturn(Optional.of(job(JOB, 999L, MEDIA)));
        ImportMediaRef ref = new ImportMediaRef(JOB, MEDIA);

        assertThat(service.attach(ROOM, List.of(ref))).containsExactly(ref);
        verify(mediaDao, never()).save(any());
        verify(imageStorage, never()).readObject(anyString());
    }

    @Test
    void unknownJobMediaIdNotListedOrGoneObjectAreSkipped() {
        ImportMediaRef unknownJob = new ImportMediaRef(404L, MEDIA);
        ImportMediaRef notListed = new ImportMediaRef(JOB, "../../other.png");
        ImportMediaRef incomplete = new ImportMediaRef(JOB, null);
        when(imageStorage.readObject(RoomsImportJobMapper.mediaKey(JOB, MEDIA))).thenReturn(null);
        ImportMediaRef gone = new ImportMediaRef(JOB, MEDIA);

        assertThat(service.attach(ROOM, List.of(unknownJob, notListed, incomplete, gone)))
                .containsExactly(unknownJob, notListed, incomplete, gone);
        verify(mediaDao, never()).save(any());
    }

    @Test
    void copyFailureOfOneFileDoesNotBlockTheNextAndNeverThrows() {
        String second = "aaaaaaaa-2222-3333-4444-555555555555.png";
        when(jobDao.findById(JOB)).thenReturn(Optional.of(job(JOB, PROJECT, MEDIA, second)));
        when(imageStorage.readObject(RoomsImportJobMapper.mediaKey(JOB, MEDIA)))
                .thenThrow(new IllegalStateException("storage down"));
        when(imageStorage.readObject(RoomsImportJobMapper.mediaKey(JOB, second))).thenReturn(BYTES);
        ImportMediaRef failing = new ImportMediaRef(JOB, MEDIA);

        List<ImportMediaRef> skipped = service.attach(ROOM, List.of(failing, new ImportMediaRef(JOB, second)));

        assertThat(skipped).containsExactly(failing);
        verify(mediaDao).save(any(RoomMediaEntity.class));
    }

    @Test
    void duplicateRefsAreAttachedOnce() {
        ImportMediaRef ref = new ImportMediaRef(JOB, MEDIA);

        assertThat(service.attach(ROOM, List.of(ref, ref))).isEmpty();
        verify(mediaDao, org.mockito.Mockito.times(1)).save(any(RoomMediaEntity.class));
    }

    @Test
    void nullOrEmptyRefsDoNothing() {
        assertThat(service.attach(ROOM, null)).isEmpty();
        assertThat(service.attach(ROOM, List.of())).isEmpty();
        verify(roomDao, never()).findById(any());
    }

    private static ProjectEntity project(Long id) {
        ProjectEntity p = new ProjectEntity();
        p.setId(id);
        return p;
    }

    private RoomsImportJobEntity job(Long id, Long projectId, String... mediaIds) {
        RoomsImportJobEntity job = new RoomsImportJobEntity();
        job.setId(id);
        job.setProject(project(projectId));
        List<DraftMedia> media = new java.util.ArrayList<>();
        for (String m : mediaIds) {
            media.add(new DraftMedia(m, "SOURCE_CROP", "image/png", null));
        }
        DraftRoom draft = new DraftRoom("t1", 0.9, null, null, null, Map.of(), media, List.of());
        job.setResult(jobMapper.resultToMap(new ImportResult(List.of(draft), List.of(), List.of(), null)));
        return job;
    }
}
