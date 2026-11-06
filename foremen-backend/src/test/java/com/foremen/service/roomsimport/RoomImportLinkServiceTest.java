package com.foremen.service.roomsimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.foremen.controller.model.RoomCreateRequest;
import com.foremen.controller.model.RoomUpdateRequest;
import com.foremen.controller.model.mapper.RoomControllerMapperImpl;
import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomsImportJobEntity;
import com.foremen.service.model.RoomServiceExtendedModel;
import com.foremen.service.roomsimport.model.ImportMediaRef;

/**
 * FOR-05-02 (Requirements 22.2, 22.3, 21.4): the {@code aiImported} marker is derived only from a
 * verified same-project job, never from request data, and is soft-failing.
 */
class RoomImportLinkServiceTest {

    private final RoomsImportJobDao jobDao = mock(RoomsImportJobDao.class);
    private final RoomDao roomDao = mock(RoomDao.class);
    private final RoomImportLinkService service = new RoomImportLinkService(jobDao, roomDao);

    @Test
    void verifiedJobMarksRoomAndSetsAppliedAtFirstTimeOnly() {
        RoomEntity room = room(1L, 5L);
        RoomsImportJobEntity job = job(9L, 5L, null);
        when(roomDao.findById(1L)).thenReturn(Optional.of(room));
        when(jobDao.findById(9L)).thenReturn(Optional.of(job));

        assertThat(service.link(1L, 9L)).isTrue();

        assertThat(room.isAiImported()).isTrue();
        assertThat(job.getAppliedAt()).isNotNull();

        LocalDateTime first = job.getAppliedAt();
        RoomEntity second = room(2L, 5L);
        when(roomDao.findById(2L)).thenReturn(Optional.of(second));
        assertThat(service.link(2L, 9L)).isTrue();
        assertThat(second.isAiImported()).isTrue();
        assertThat(job.getAppliedAt()).isEqualTo(first);
    }

    @Test
    void foreignProjectJobLeavesRoomUnmarked() {
        RoomEntity room = room(1L, 5L);
        RoomsImportJobEntity job = job(9L, 6L, null);
        when(roomDao.findById(1L)).thenReturn(Optional.of(room));
        when(jobDao.findById(9L)).thenReturn(Optional.of(job));

        assertThat(service.link(1L, 9L)).isFalse();

        assertThat(room.isAiImported()).isFalse();
        assertThat(job.getAppliedAt()).isNull();
        verify(roomDao, never()).save(any());
    }

    @Test
    void unknownJobOrNullIdIsRejectedWithoutThrowing() {
        when(roomDao.findById(1L)).thenReturn(Optional.of(room(1L, 5L)));
        when(jobDao.findById(404L)).thenReturn(Optional.empty());

        assertThat(service.link(1L, 404L)).isFalse();
        assertThat(service.link(1L, null)).isFalse();
    }

    @Test
    void storageFailureIsSwallowed() {
        when(roomDao.findById(1L)).thenThrow(new IllegalStateException("db"));

        assertThat(service.link(1L, 9L)).isFalse();
    }

    @Test
    void createRequestCarriesImportFieldsButUpdateMappingDropsThemAndNeverSetsMarker() {
        RoomControllerMapperImpl mapper = new RoomControllerMapperImpl();
        ImportMediaRef ref = new ImportMediaRef(9L, "a.png");
        RoomCreateRequest create = new RoomCreateRequest(5L, 2L, "Kitchen", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, java.util.List.of(ref), 9L);

        RoomServiceExtendedModel model = mapper.toServiceExtendedModel(create);

        assertThat(model.getImportMedia()).containsExactly(ref);
        assertThat(model.getImportJobId()).isEqualTo(9L);
        assertThat(model.isAiImported()).isFalse();

        RoomServiceExtendedModel update = mapper.toUpdateServiceExtendedModel(
                mock(RoomUpdateRequest.class));
        assertThat(update.getImportMedia()).isNull();
        assertThat(update.getImportJobId()).isNull();
        assertThat(update.isAiImported()).isFalse();
    }

    @Test
    void createRequestWithoutImportFieldsMapsToNullImportData() {
        RoomCreateRequest create = new RoomCreateRequest(5L, 2L, "Hall", null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);

        RoomServiceExtendedModel model = new RoomControllerMapperImpl().toServiceExtendedModel(create);

        assertThat(model.getImportMedia()).isNull();
        assertThat(model.getImportJobId()).isNull();
    }

    private static RoomEntity room(Long id, Long projectId) {
        RoomEntity r = new RoomEntity();
        r.setId(id);
        r.setProject(project(projectId));
        return r;
    }

    private static RoomsImportJobEntity job(Long id, Long projectId, LocalDateTime appliedAt) {
        RoomsImportJobEntity j = new RoomsImportJobEntity();
        j.setId(id);
        j.setProject(project(projectId));
        j.setAppliedAt(appliedAt);
        return j;
    }

    private static ProjectEntity project(Long id) {
        ProjectEntity p = new ProjectEntity();
        p.setId(id);
        return p;
    }
}
