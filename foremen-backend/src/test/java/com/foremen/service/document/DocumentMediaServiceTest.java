package com.foremen.service.document;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.dao.DocumentMediaDao;
import com.foremen.dao.DocumentSignatureDao;
import com.foremen.dao.SignableDocumentDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.DocumentMediaEntity;
import com.foremen.dao.model.DocumentMediaKind;
import com.foremen.dao.model.DocumentSignatureEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.dao.model.SignatureStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.document.mapper.DocumentDtoMapper;
import com.foremen.service.image.ImageStorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DocumentMediaService} (FOR-05-08-document-signing, task 6.2), exercising the
 * upload validation surface (R7.3) and the signed-evidence delete lock (R7.4) with stubbed DAOs, a
 * mocked {@link ImageStorage object storage} seam, a mocked {@link DocumentDtoMapper}, and a
 * manually-driven {@link SecurityContextHolder} — following the
 * {@code OfferDiscountServiceTest} / {@code EstimateServiceTest} Mockito convention for a service
 * with DAO collaborators (no Spring context).
 *
 * <p><b>Upload validation (R7.3).</b> {@link DocumentMediaService#upload} rejects an empty/null
 * upload, a disallowed content type, an oversized file ({@code > 15 MB}), or a {@code null} kind
 * with {@code 400 error.document.media.invalid}. Each rejection stores nothing — the tests assert
 * {@code objectStorage.store} is NEVER invoked and no media row is saved. The happy path stores the
 * bytes and persists the metadata + kind.
 *
 * <p><b>Signed-evidence delete lock (R7.4).</b> {@link DocumentMediaService#delete} refuses to
 * remove a media row whose {@code storageUri} is the {@code evidenceUri} of a {@code SIGNED}
 * signature on the same document with {@code 409 error.document.media.locked}, storing nothing.
 * Media not backing a signed signature (no signature, a different evidence uri, or only a
 * {@code PENDING}/{@code DECLINED} signature) deletes normally and reclaims the orphaned object.
 *
 * <p>The security context is set to a numeric principal id resolving to a {@link UserEntity} for the
 * {@code uploadedBy} provenance (mirroring {@code OfferService}/{@code OfferDiscountService} actor
 * resolution); it is cleared per test so the suite re-runs cleanly.
 *
 * <p><b>Validates: Requirements 7.3, 7.4</b>
 */
@ExtendWith(MockitoExtension.class)
class DocumentMediaServiceTest {

    @Mock
    private DocumentMediaDao mediaDao;
    @Mock
    private DocumentSignatureDao signatureDao;
    @Mock
    private SignableDocumentDao documentDao;
    @Mock
    private UserDao userDao;
    @Mock
    private ImageStorage objectStorage;
    @Mock
    private DocumentDtoMapper mapper;

    private DocumentMediaService service;

    private static final Long DOCUMENT_ID = 500L;
    private static final Long MEDIA_ID = 900L;
    private static final Long UPLOADER_USER_ID = 11L;
    private static final String STORED_KEY = "document-media/abc-123";

    private static final long MAX_SIZE_BYTES = DataSize.ofMegabytes(15).toBytes();

    @BeforeEach
    void setUp() {
        service = new DocumentMediaService(mediaDao, signatureDao, documentDao, userDao, objectStorage, mapper);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------------------------------
    // R7.3 — upload rejects empty / disallowed-type / oversized / null-kind, storing nothing
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("upload: null file rejected 400 error.document.media.invalid, stores nothing (R7.3)")
    void uploadNullFileRejected() {
        authenticateUploader();
        when(documentDao.findById(DOCUMENT_ID)).thenReturn(Optional.of(document()));

        assertApiError(() -> service.upload(DOCUMENT_ID, null, DocumentMediaKind.SCAN),
                HttpStatus.BAD_REQUEST, "error.document.media.invalid");

        assertNothingStored();
    }

    @Test
    @DisplayName("upload: empty file rejected 400 error.document.media.invalid, stores nothing (R7.3)")
    void uploadEmptyFileRejected() {
        authenticateUploader();
        when(documentDao.findById(DOCUMENT_ID)).thenReturn(Optional.of(document()));

        MultipartFile empty = new MockMultipartFile("file", "scan.pdf", "application/pdf", new byte[0]);

        assertApiError(() -> service.upload(DOCUMENT_ID, empty, DocumentMediaKind.SCAN),
                HttpStatus.BAD_REQUEST, "error.document.media.invalid");

        assertNothingStored();
    }

    @Test
    @DisplayName("upload: null kind rejected 400 error.document.media.invalid, stores nothing (R7.3)")
    void uploadNullKindRejected() {
        authenticateUploader();
        when(documentDao.findById(DOCUMENT_ID)).thenReturn(Optional.of(document()));

        MultipartFile pdf = new MockMultipartFile("file", "scan.pdf", "application/pdf", new byte[] {1, 2, 3});

        assertApiError(() -> service.upload(DOCUMENT_ID, pdf, null),
                HttpStatus.BAD_REQUEST, "error.document.media.invalid");

        assertNothingStored();
    }

    @Test
    @DisplayName("upload: disallowed content type rejected 400 error.document.media.invalid, stores nothing (R7.3)")
    void uploadDisallowedContentTypeRejected() {
        authenticateUploader();
        when(documentDao.findById(DOCUMENT_ID)).thenReturn(Optional.of(document()));

        MultipartFile gif = new MockMultipartFile("file", "evil.gif", "image/gif", new byte[] {1, 2, 3});

        assertApiError(() -> service.upload(DOCUMENT_ID, gif, DocumentMediaKind.SCAN),
                HttpStatus.BAD_REQUEST, "error.document.media.invalid");

        assertNothingStored();
    }

    @Test
    @DisplayName("upload: null content type rejected 400 error.document.media.invalid, stores nothing (R7.3)")
    void uploadNullContentTypeRejected() {
        authenticateUploader();
        when(documentDao.findById(DOCUMENT_ID)).thenReturn(Optional.of(document()));

        MultipartFile noType = new MockMultipartFile("file", "scan.pdf", null, new byte[] {1, 2, 3});

        assertApiError(() -> service.upload(DOCUMENT_ID, noType, DocumentMediaKind.SCAN),
                HttpStatus.BAD_REQUEST, "error.document.media.invalid");

        assertNothingStored();
    }

    @Test
    @DisplayName("upload: oversized file (> 15 MB) rejected 400 error.document.media.invalid, stores nothing (R7.3)")
    void uploadOversizedRejected() {
        authenticateUploader();
        when(documentDao.findById(DOCUMENT_ID)).thenReturn(Optional.of(document()));

        // One byte over the 15 MB limit.
        byte[] tooBig = new byte[(int) (MAX_SIZE_BYTES + 1)];
        MultipartFile oversized = new MockMultipartFile("file", "huge.pdf", "application/pdf", tooBig);

        assertApiError(() -> service.upload(DOCUMENT_ID, oversized, DocumentMediaKind.ATTACHMENT),
                HttpStatus.BAD_REQUEST, "error.document.media.invalid");

        assertNothingStored();
    }

    @Test
    @DisplayName("upload: all allowed content types accepted and stored with metadata + kind (R7.1, R7.2, R7.3)")
    void uploadAllowedTypesStored() {
        for (String contentType : List.of("application/pdf", "image/png", "image/jpeg", "image/webp")) {
            SecurityContextHolder.clearContext();
            authenticateUploader();
            SignableDocumentEntity document = document();
            when(documentDao.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));
            when(objectStorage.store(any(MultipartFile.class), anyString())).thenReturn(STORED_KEY);
            when(mediaDao.save(any(DocumentMediaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            DocumentMediaSummaryDto dto = new DocumentMediaSummaryDto(
                    MEDIA_ID, "scan", contentType, 3L, STORED_KEY, DocumentMediaKind.SCAN);
            when(mapper.toDto(any(DocumentMediaEntity.class))).thenReturn(dto);

            MultipartFile file = new MockMultipartFile("file", "scan", contentType, new byte[] {1, 2, 3});

            DocumentMediaSummaryDto result = service.upload(DOCUMENT_ID, file, DocumentMediaKind.SCAN);

            assertThat(result).isSameAs(dto);
            verify(objectStorage).store(file, "document-media");

            org.mockito.ArgumentCaptor<DocumentMediaEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(DocumentMediaEntity.class);
            verify(mediaDao).save(captor.capture());
            DocumentMediaEntity saved = captor.getValue();
            assertThat(saved.getDocument()).isSameAs(document);
            assertThat(saved.getContentType()).isEqualTo(contentType);
            assertThat(saved.getStorageUri()).isEqualTo(STORED_KEY);
            assertThat(saved.getKind()).isEqualTo(DocumentMediaKind.SCAN);
            assertThat(saved.getSizeBytes()).isEqualTo(3L);
            assertThat(saved.getUploadedBy().getId()).isEqualTo(UPLOADER_USER_ID);

            // Reset for the next content type in the loop.
            org.mockito.Mockito.reset(documentDao, objectStorage, mediaDao, mapper, userDao);
        }
    }

    @Test
    @DisplayName("upload: content type match is case-insensitive (R7.3)")
    void uploadContentTypeCaseInsensitive() {
        authenticateUploader();
        SignableDocumentEntity document = document();
        when(documentDao.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));
        when(objectStorage.store(any(MultipartFile.class), anyString())).thenReturn(STORED_KEY);
        when(mediaDao.save(any(DocumentMediaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toDto(any(DocumentMediaEntity.class))).thenReturn(
                new DocumentMediaSummaryDto(MEDIA_ID, "scan", "APPLICATION/PDF", 3L, STORED_KEY, DocumentMediaKind.SCAN));

        MultipartFile file = new MockMultipartFile("file", "scan.pdf", "APPLICATION/PDF", new byte[] {1, 2, 3});

        service.upload(DOCUMENT_ID, file, DocumentMediaKind.SCAN);

        verify(objectStorage).store(file, "document-media");
        verify(mediaDao).save(any(DocumentMediaEntity.class));
    }

    @Test
    @DisplayName("upload: file at exactly the 15 MB limit is accepted (R7.3 boundary)")
    void uploadExactlyAtLimitAccepted() {
        authenticateUploader();
        SignableDocumentEntity document = document();
        when(documentDao.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));
        when(objectStorage.store(any(MultipartFile.class), anyString())).thenReturn(STORED_KEY);
        when(mediaDao.save(any(DocumentMediaEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mapper.toDto(any(DocumentMediaEntity.class))).thenReturn(
                new DocumentMediaSummaryDto(MEDIA_ID, "huge", "application/pdf", MAX_SIZE_BYTES, STORED_KEY,
                        DocumentMediaKind.ATTACHMENT));

        byte[] atLimit = new byte[(int) MAX_SIZE_BYTES];
        MultipartFile file = new MockMultipartFile("file", "huge.pdf", "application/pdf", atLimit);

        service.upload(DOCUMENT_ID, file, DocumentMediaKind.ATTACHMENT);

        verify(objectStorage).store(file, "document-media");
        verify(mediaDao).save(any(DocumentMediaEntity.class));
    }

    @Test
    @DisplayName("upload: unknown document rejected 404 error.entity.not.found before any validation/store (R7.1)")
    void uploadUnknownDocumentRejected() {
        when(documentDao.findById(DOCUMENT_ID)).thenReturn(Optional.empty());

        MultipartFile pdf = new MockMultipartFile("file", "scan.pdf", "application/pdf", new byte[] {1, 2, 3});

        assertApiError(() -> service.upload(DOCUMENT_ID, pdf, DocumentMediaKind.SCAN),
                HttpStatus.NOT_FOUND, "error.entity.not.found");

        assertNothingStored();
    }

    // ------------------------------------------------------------------------------------------
    // R7.4 — delete refuses media backing a SIGNED signature (409 error.document.media.locked)
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("delete: media backing a SIGNED signature rejected 409 error.document.media.locked, deletes nothing (R7.4)")
    void deleteSignedEvidenceLocked() {
        DocumentMediaEntity media = media(STORED_KEY);
        when(mediaDao.findById(MEDIA_ID)).thenReturn(Optional.of(media));
        when(signatureDao.findByDocumentId(DOCUMENT_ID)).thenReturn(List.of(
                signature(SignatureStatus.SIGNED, STORED_KEY)));

        assertApiError(() -> service.delete(MEDIA_ID), HttpStatus.CONFLICT, "error.document.media.locked");

        verify(mediaDao, never()).delete(any(DocumentMediaEntity.class));
        verify(objectStorage, never()).deleteIfOrphan(anyString());
    }

    @Test
    @DisplayName("delete: media with no matching signature is deleted and object reclaimed (R7.4)")
    void deleteNotEvidenceSucceeds() {
        DocumentMediaEntity media = media(STORED_KEY);
        when(mediaDao.findById(MEDIA_ID)).thenReturn(Optional.of(media));
        when(signatureDao.findByDocumentId(DOCUMENT_ID)).thenReturn(List.of());

        service.delete(MEDIA_ID);

        verify(mediaDao).delete(media);
        verify(objectStorage).deleteIfOrphan(STORED_KEY);
    }

    @Test
    @DisplayName("delete: media whose uri backs only a PENDING signature is deleted (R7.4)")
    void deletePendingEvidenceSucceeds() {
        DocumentMediaEntity media = media(STORED_KEY);
        when(mediaDao.findById(MEDIA_ID)).thenReturn(Optional.of(media));
        when(signatureDao.findByDocumentId(DOCUMENT_ID)).thenReturn(List.of(
                signature(SignatureStatus.PENDING, STORED_KEY),
                signature(SignatureStatus.DECLINED, STORED_KEY)));

        service.delete(MEDIA_ID);

        verify(mediaDao).delete(media);
        verify(objectStorage).deleteIfOrphan(STORED_KEY);
    }

    @Test
    @DisplayName("delete: SIGNED signature with a different evidence uri does not lock the media (R7.4)")
    void deleteSignedButDifferentUriSucceeds() {
        DocumentMediaEntity media = media(STORED_KEY);
        when(mediaDao.findById(MEDIA_ID)).thenReturn(Optional.of(media));
        when(signatureDao.findByDocumentId(DOCUMENT_ID)).thenReturn(List.of(
                signature(SignatureStatus.SIGNED, "document-media/some-other-key")));

        service.delete(MEDIA_ID);

        verify(mediaDao).delete(media);
        verify(objectStorage).deleteIfOrphan(STORED_KEY);
    }

    @Test
    @DisplayName("delete: unknown media rejected 404 error.entity.not.found (R7.4)")
    void deleteUnknownMediaRejected() {
        when(mediaDao.findById(MEDIA_ID)).thenReturn(Optional.empty());

        assertApiError(() -> service.delete(MEDIA_ID), HttpStatus.NOT_FOUND, "error.entity.not.found");

        verify(mediaDao, never()).delete(any(DocumentMediaEntity.class));
        verify(objectStorage, never()).deleteIfOrphan(anyString());
    }

    // ------------------------------------------------------------------------------------------
    // fixtures / helpers
    // ------------------------------------------------------------------------------------------

    private SignableDocumentEntity document() {
        ProjectEntity project = new ProjectEntity();
        project.setId(42L);
        SignableDocumentEntity document = new SignableDocumentEntity();
        document.setId(DOCUMENT_ID);
        document.setProject(project);
        return document;
    }

    private DocumentMediaEntity media(String storageUri) {
        DocumentMediaEntity media = new DocumentMediaEntity();
        media.setId(MEDIA_ID);
        media.setDocument(document());
        media.setStorageUri(storageUri);
        media.setKind(DocumentMediaKind.SCAN);
        return media;
    }

    private DocumentSignatureEntity signature(SignatureStatus status, String evidenceUri) {
        DocumentSignatureEntity signature = new DocumentSignatureEntity();
        signature.setStatus(status);
        signature.setEvidenceUri(evidenceUri);
        return signature;
    }

    /** No bytes stored and no media row persisted — the rejection side-effect assertion (R7.3). */
    private void assertNothingStored() {
        verify(objectStorage, never()).store(any(MultipartFile.class), anyString());
        verify(mediaDao, never()).save(any(DocumentMediaEntity.class));
    }

    /** Authenticate as the uploader: numeric principal id resolving to a user for uploadedBy. */
    private void authenticateUploader() {
        UserEntity user = new UserEntity();
        user.setId(UPLOADER_USER_ID);
        lenient().when(userDao.findById(UPLOADER_USER_ID)).thenReturn(Optional.of(user));
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(String.valueOf(UPLOADER_USER_ID), "n/a", authorities);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static void assertApiError(Runnable action, HttpStatus status, String messageCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(status);
                    assertThat(api.getMessageCode()).isEqualTo(messageCode);
                });
    }
}
