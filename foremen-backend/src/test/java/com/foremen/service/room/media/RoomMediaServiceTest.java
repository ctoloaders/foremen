package com.foremen.service.room.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.unit.DataSize;

import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomMediaDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomMediaEntity;
import com.foremen.dao.model.RoomMediaKind;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.RoomService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.image.ImageStorage;

/** FOR-05-02 (Requirements 10.2, 10.3, 10.6, 14.3): room media validation, scoping and audit. */
class RoomMediaServiceTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final byte[] PDF = "%PDF-1.7 body".getBytes();

    private final RoomMediaDao mediaDao = mock(RoomMediaDao.class);
    private final RoomDao roomDao = mock(RoomDao.class);
    private final RoomService roomService = mock(RoomService.class);
    private final UserDao userDao = mock(UserDao.class);
    private final ImageStorage storage = mock(ImageStorage.class);
    private final RoomMediaSupport support = mock(RoomMediaSupport.class);
    private final AuditLogDao auditLogDao = mock(AuditLogDao.class);

    private RoomMediaService service(int maxFiles, DataSize maxSize) {
        return new RoomMediaService(mediaDao, roomDao, roomService, userDao, storage, support, auditLogDao,
                maxSize, maxFiles);
    }

    @BeforeEach
    void setUp() {
        RoomEntity room = new RoomEntity();
        room.setId(7L);
        when(roomDao.findById(7L)).thenReturn(Optional.of(room));
        UserEntity user = new UserEntity();
        when(userDao.findById(3L)).thenReturn(Optional.of(user));
        when(storage.storeObject(any(), anyString())).thenReturn("room-media/x.png");
        when(mediaDao.save(any(RoomMediaEntity.class))).thenAnswer(inv -> {
            RoomMediaEntity m = inv.getArgument(0);
            m.setId(11L);
            return m;
        });
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("3", "n", List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static MockMultipartFile file(String type, byte[] bytes) {
        return new MockMultipartFile("files", "../dir/p.png", type, bytes);
    }

    @Test
    void uploadStoresUploadKindAndAudits() {
        List<RoomMediaDto> out = service(20, DataSize.ofMegabytes(20))
                .upload(7L, List.of(file("image/png", PNG)));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).kind()).isEqualTo(RoomMediaKind.UPLOAD);
        assertThat(out.get(0).fileName()).isEqualTo("p.png");
        verify(auditLogDao).save(any());
    }

    @Test
    void pdfIsAccepted() {
        assertThat(service(20, DataSize.ofMegabytes(20)).upload(7L, List.of(file("application/pdf", PDF))))
                .hasSize(1);
    }

    @Test
    void disallowedDeclaredTypeIsRejectedAndNothingStored() {
        assertThatThrownBy(() -> service(20, DataSize.ofMegabytes(20))
                .upload(7L, List.of(file("text/html", PNG))))
                .isInstanceOf(ForemenApiException.class)
                .hasMessageContaining("error.room.media.type");
        verify(storage, never()).storeObject(any(), anyString());
    }

    @Test
    void declaredTypeNotMatchingMagicBytesIsRejected() {
        assertThatThrownBy(() -> service(20, DataSize.ofMegabytes(20))
                .upload(7L, List.of(file("image/png", PDF))))
                .isInstanceOf(ForemenApiException.class)
                .hasMessageContaining("error.room.media.type");
    }

    @Test
    void oversizedFileIsRejected() {
        assertThatThrownBy(() -> service(20, DataSize.ofBytes(8)).upload(7L, List.of(file("image/png", PNG))))
                .isInstanceOf(ForemenApiException.class)
                .hasMessageContaining("error.room.media.size");
    }

    @Test
    void exceedingFileCountIsRejectedBeforeStoring() {
        when(mediaDao.countByRoomId(7L)).thenReturn(1L);
        assertThatThrownBy(() -> service(2, DataSize.ofMegabytes(20))
                .upload(7L, List.of(file("image/png", PNG), file("image/png", PNG))))
                .isInstanceOf(ForemenApiException.class)
                .hasMessageContaining("error.room.media.count");
        verify(storage, never()).storeObject(any(), anyString());
    }

    @Test
    void mediaOfAnotherRoomIsNotFound() {
        RoomEntity other = new RoomEntity();
        other.setId(99L);
        RoomMediaEntity media = new RoomMediaEntity();
        media.setRoom(other);
        when(mediaDao.findById(5L)).thenReturn(Optional.of(media));

        assertThatThrownBy(() -> service(20, DataSize.ofMegabytes(20)).delete(7L, 5L))
                .isInstanceOf(ForemenApiException.class);
        verify(mediaDao, never()).delete(any(RoomMediaEntity.class));
    }

    @Test
    void deleteRemovesRowAuditsAndSchedulesObjectCleanup() {
        RoomEntity room = new RoomEntity();
        room.setId(7L);
        RoomMediaEntity media = new RoomMediaEntity();
        media.setId(5L);
        media.setRoom(room);
        media.setFileName("a.png");
        media.setContentType("image/png");
        media.setSizeBytes(1L);
        media.setKind(RoomMediaKind.SOURCE_CROP);
        media.setStorageUri("room-media/a.png");
        when(mediaDao.findById(5L)).thenReturn(Optional.of(media));

        service(20, DataSize.ofMegabytes(20)).delete(7L, 5L);

        verify(mediaDao).delete(media);
        verify(auditLogDao).save(any());
        verify(support).deleteObjectsAfterCommit(List.of("room-media/a.png"));
    }
}
