package com.foremen.service.clientmessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.config.image.ImageStorageProperties;
import com.foremen.dao.ClientMessageMediaDao;
import com.foremen.dao.model.ClientMessageEntity;
import com.foremen.dao.model.ClientMessageMediaEntity;
import com.foremen.dao.model.MediaKind;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.image.ImageStorage;

/**
 * FOR-06-12 task 10.1 (Requirements 4.2, 4.3) — plain Mockito unit tests for
 * {@link ClientMessageMediaService}, the media-attachment service with its own content-type
 * allow-list and per-kind size caps. The DAO and the object-storage seam are mocked; the uploads are
 * {@link MockMultipartFile} fixtures.
 *
 * <p>Covers:
 * <ul>
 *   <li><b>kind derivation</b> — {@code image/*} &rarr; IMAGE, {@code application/pdf} &rarr;
 *       DOCUMENT, {@code video/*} &rarr; VIDEO (from the content type);</li>
 *   <li><b>allow-list (R4.2)</b> — a disallowed type (and an empty upload / missing content type) is
 *       rejected 400 {@code error.clientMessage.media.invalid}, storing nothing;</li>
 *   <li><b>per-kind caps (R4.3)</b> — image/doc cap 10&nbsp;MB, video cap 100&nbsp;MB; an oversize
 *       file per kind is rejected 400 {@code error.clientMessage.media.tooLarge}, storing nothing; a
 *       file at the cap is accepted.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClientMessageMediaServiceTest {

    private static final long TEN_MB = DataSize.ofMegabytes(10).toBytes();
    private static final long HUNDRED_MB = DataSize.ofMegabytes(100).toBytes();

    @Mock
    private ClientMessageMediaDao mediaDao;
    @Mock
    private ImageStorage objectStorage;
    @Mock
    private ImageStorageProperties imageStorageProperties;

    private ClientMessageMediaService service;

    private final ClientMessageEntity message = new ClientMessageEntity();
    private final UserEntity uploader = new UserEntity();

    @BeforeEach
    void setUp() {
        service = new ClientMessageMediaService(mediaDao, objectStorage, imageStorageProperties);
        lenient().when(mediaDao.save(any(ClientMessageMediaEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(objectStorage.storeObject(any(MultipartFile.class), eq("client-messages")))
                .thenReturn("client-messages/obj-key");
    }

    // =======================================================================
    // kind derivation (R4.2)
    // =======================================================================

    @Nested
    @DisplayName("kind derivation from content type")
    class KindDerivation {

        @Test
        @DisplayName("image/png -> IMAGE")
        void pngIsImage() {
            ClientMessageMediaEntity saved = service.store(message, file("image/png", 1_000), uploader);
            assertThat(saved.getKind()).isEqualTo(MediaKind.IMAGE);
        }

        @Test
        @DisplayName("image/jpeg -> IMAGE")
        void jpegIsImage() {
            ClientMessageMediaEntity saved = service.store(message, file("image/jpeg", 1_000), uploader);
            assertThat(saved.getKind()).isEqualTo(MediaKind.IMAGE);
        }

        @Test
        @DisplayName("image/webp -> IMAGE")
        void webpIsImage() {
            ClientMessageMediaEntity saved = service.store(message, file("image/webp", 1_000), uploader);
            assertThat(saved.getKind()).isEqualTo(MediaKind.IMAGE);
        }

        @Test
        @DisplayName("application/pdf -> DOCUMENT")
        void pdfIsDocument() {
            ClientMessageMediaEntity saved = service.store(message, file("application/pdf", 1_000), uploader);
            assertThat(saved.getKind()).isEqualTo(MediaKind.DOCUMENT);
        }

        @Test
        @DisplayName("video/mp4 -> VIDEO")
        void mp4IsVideo() {
            ClientMessageMediaEntity saved = service.store(message, file("video/mp4", 1_000), uploader);
            assertThat(saved.getKind()).isEqualTo(MediaKind.VIDEO);
        }

        @Test
        @DisplayName("video/quicktime -> VIDEO")
        void quicktimeIsVideo() {
            ClientMessageMediaEntity saved = service.store(message, file("video/quicktime", 1_000), uploader);
            assertThat(saved.getKind()).isEqualTo(MediaKind.VIDEO);
        }

        @Test
        @DisplayName("a mixed-case content type is normalized (IMAGE/PNG -> IMAGE)")
        void contentTypeIsCaseInsensitive() {
            ClientMessageMediaEntity saved = service.store(message, file("IMAGE/PNG", 1_000), uploader);
            assertThat(saved.getKind()).isEqualTo(MediaKind.IMAGE);
        }
    }

    // =======================================================================
    // allow-list (R4.2)
    // =======================================================================

    @Nested
    @DisplayName("allow-list — disallowed type rejected (R4.2)")
    class AllowList {

        @Test
        @DisplayName("a disallowed type is rejected 400 error.clientMessage.media.invalid, storing nothing")
        void disallowedTypeRejected() {
            assertApiError(() -> service.store(message, file("application/zip", 1_000), uploader),
                    HttpStatus.BAD_REQUEST, "error.clientMessage.media.invalid");
            verifyNothingStored();
        }

        @Test
        @DisplayName("an octet-stream type is rejected 400 error.clientMessage.media.invalid")
        void octetStreamRejected() {
            assertApiError(() -> service.store(message, file("application/octet-stream", 1_000), uploader),
                    HttpStatus.BAD_REQUEST, "error.clientMessage.media.invalid");
            verifyNothingStored();
        }

        @Test
        @DisplayName("an empty upload is rejected 400 error.clientMessage.media.invalid")
        void emptyUploadRejected() {
            MultipartFile empty = new MockMultipartFile("f", "e.png", "image/png", new byte[0]);
            assertApiError(() -> service.store(message, empty, uploader),
                    HttpStatus.BAD_REQUEST, "error.clientMessage.media.invalid");
            verifyNothingStored();
        }

        @Test
        @DisplayName("a missing content type is rejected 400 error.clientMessage.media.invalid")
        void missingContentTypeRejected() {
            MultipartFile noType = new MockMultipartFile("f", "e.bin", null, new byte[]{1, 2, 3});
            assertApiError(() -> service.store(message, noType, uploader),
                    HttpStatus.BAD_REQUEST, "error.clientMessage.media.invalid");
            verifyNothingStored();
        }
    }

    // =======================================================================
    // per-kind size caps (R4.3)
    // =======================================================================

    @Nested
    @DisplayName("per-kind size caps (R4.3)")
    class SizeCaps {

        @Test
        @DisplayName("an image at the 10 MB cap is accepted")
        void imageAtCapAccepted() {
            ClientMessageMediaEntity saved = service.store(message, sized("image/png", TEN_MB), uploader);
            assertThat(saved.getKind()).isEqualTo(MediaKind.IMAGE);
        }

        @Test
        @DisplayName("an image over 10 MB is rejected 400 error.clientMessage.media.tooLarge")
        void imageOverCapRejected() {
            assertApiError(() -> service.store(message, sized("image/png", TEN_MB + 1), uploader),
                    HttpStatus.BAD_REQUEST, "error.clientMessage.media.tooLarge");
            verifyNothingStored();
        }

        @Test
        @DisplayName("a document over 10 MB is rejected 400 error.clientMessage.media.tooLarge")
        void documentOverCapRejected() {
            assertApiError(() -> service.store(message, sized("application/pdf", TEN_MB + 1), uploader),
                    HttpStatus.BAD_REQUEST, "error.clientMessage.media.tooLarge");
            verifyNothingStored();
        }

        @Test
        @DisplayName("a video between 10 MB and 100 MB is accepted (its cap is 100 MB)")
        void videoWithinVideoCapAccepted() {
            ClientMessageMediaEntity saved = service.store(message, sized("video/mp4", TEN_MB + 1), uploader);
            assertThat(saved.getKind()).isEqualTo(MediaKind.VIDEO);
        }

        @Test
        @DisplayName("a video at the 100 MB cap is accepted")
        void videoAtCapAccepted() {
            ClientMessageMediaEntity saved = service.store(message, sized("video/mp4", HUNDRED_MB), uploader);
            assertThat(saved.getKind()).isEqualTo(MediaKind.VIDEO);
        }

        @Test
        @DisplayName("a video over 100 MB is rejected 400 error.clientMessage.media.tooLarge")
        void videoOverCapRejected() {
            assertApiError(() -> service.store(message, sized("video/mp4", HUNDRED_MB + 1), uploader),
                    HttpStatus.BAD_REQUEST, "error.clientMessage.media.tooLarge");
            verifyNothingStored();
        }
    }

    // =======================================================================
    // attachAll — null/empty attaches nothing
    // =======================================================================

    @Nested
    @DisplayName("attachAll")
    class AttachAll {

        @Test
        @DisplayName("a null file list attaches nothing (text-only message)")
        void nullListAttachesNothing() {
            assertThat(service.attachAll(message, null, uploader)).isEmpty();
            verifyNothingStored();
        }

        @Test
        @DisplayName("an empty file list attaches nothing")
        void emptyListAttachesNothing() {
            assertThat(service.attachAll(message, List.of(), uploader)).isEmpty();
            verifyNothingStored();
        }

        @Test
        @DisplayName("a valid batch stores each file")
        void validBatchStoresEach() {
            List<ClientMessageMediaEntity> saved = service.attachAll(message,
                    List.of(file("image/png", 10), file("application/pdf", 10)), uploader);
            assertThat(saved).hasSize(2);
            verify(mediaDao, org.mockito.Mockito.times(2)).save(any());
        }
    }

    // =======================================================================
    // helpers
    // =======================================================================

    private void verifyNothingStored() {
        verify(objectStorage, never()).storeObject(any(), any());
        verify(mediaDao, never()).save(any());
    }

    private static MultipartFile file(String contentType, int bytes) {
        return new MockMultipartFile("f", "upload", contentType, new byte[Math.max(1, bytes)]);
    }

    /** A multipart file whose reported size is {@code size} without allocating that many bytes. */
    private static MultipartFile sized(String contentType, long size) {
        return new MockMultipartFile("f", "upload", contentType, new byte[1]) {
            @Override
            public long getSize() {
                return size;
            }

            @Override
            public boolean isEmpty() {
                return size == 0;
            }
        };
    }

    private static void assertApiError(Runnable action, HttpStatus status, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(status.value());
                    assertThat(api.getMessageCode()).isEqualTo(code);
                });
    }
}
