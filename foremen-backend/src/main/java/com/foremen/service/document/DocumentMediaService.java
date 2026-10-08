package com.foremen.service.document;

import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.dao.DocumentMediaDao;
import com.foremen.dao.DocumentSignatureDao;
import com.foremen.dao.SignableDocumentDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.DocumentMediaEntity;
import com.foremen.dao.model.DocumentMediaKind;
import com.foremen.dao.model.DocumentSignatureEntity;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.dao.model.SignatureStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.document.mapper.DocumentDtoMapper;

import lombok.RequiredArgsConstructor;

/**
 * FOR-05-08 (Requirements 7.1, 7.2, 7.3, 7.4): the DocumentMedia service — upload, listing and
 * deletion of the evidence/attachment files of a {@link SignableDocumentEntity}, mirroring the
 * {@code RoomMedia}/{@code ProjectMedia} convention: the bytes live in object storage (FOR-12, the
 * shared {@link ImageStorage} seam) and only metadata + {@link DocumentMediaKind kind} is kept in
 * the DB (R7.2).
 *
 * <p><b>Project scope.</b> Media is a collection child of its document and resolves to a project via
 * {@code document.project.id} (R7.1). This service is NOT a standalone {@code ProjectScopedService}
 * CRUD resource — media is reached through its owning {@code SignableDocument} (the
 * {@code SIGNABLE_DOCUMENTS} project-scoped resource), so every operation here loads the document
 * first and the surrounding controller/service enforce the project-membership gate.
 *
 * <p><b>Validation (R7.3).</b> {@link #upload} rejects an empty upload, a disallowed content type,
 * or an oversized file with {@code 400 error.document.media.invalid}, storing nothing.
 *
 * <p><b>Signed-evidence lock (R7.4).</b> {@link #delete} refuses to remove a media row that is the
 * completion evidence of a {@code SIGNED} signature with {@code 409 error.document.media.locked};
 * the signed artifact and its evidence are immutable.
 */
@Service
@RequiredArgsConstructor
public class DocumentMediaService {

    /** 400 for an empty / disallowed-type / oversized upload (R7.3). */
    private static final String MEDIA_INVALID = "error.document.media.invalid";

    /** 409 when deleting media that backs a {@code SIGNED} signature (R7.4). */
    private static final String MEDIA_LOCKED = "error.document.media.locked";

    /** 404 for a missing document / media. */
    private static final String NOT_FOUND = "error.entity.not.found";

    /** Namespace under which document-media objects are stored in object storage (R7.2). */
    private static final String STORAGE_NAMESPACE = "document-media";

    /**
     * Allowed media content types (R7.3): the wet-ink scan / tablet-parafka / rendered-body /
     * attachment surface is PDFs and the standard raster image types. Anything else is rejected.
     */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "image/png",
            "image/jpeg",
            "image/webp");

    /** Maximum accepted upload size (R7.3). */
    private static final long MAX_SIZE_BYTES = DataSize.ofMegabytes(15).toBytes();

    private final DocumentMediaDao mediaDao;
    private final DocumentSignatureDao signatureDao;
    private final SignableDocumentDao documentDao;
    private final UserDao userDao;
    private final ImageStorage objectStorage;
    private final DocumentDtoMapper mapper;

    /**
     * Validate and store an uploaded media file for a document, persisting its metadata + {@code
     * kind} with the bytes in object storage (R7.1, R7.2, R7.3).
     *
     * @param documentId the owning document id (project scope via {@code document.project.id})
     * @param file       the uploaded file; must be a permitted type within the size limit
     * @param kind       the media kind ({@code SCAN}/{@code TABLET_INITIAL}/{@code RENDERED_BODY}/
     *                   {@code ATTACHMENT})
     * @return the stored media summary
     */
    @Transactional
    public DocumentMediaSummaryDto upload(Long documentId, MultipartFile file, DocumentMediaKind kind) {
        SignableDocumentEntity document = requireDocument(documentId);
        validate(file, kind);

        // FOR-12 object storage (R7.2): the shared ImageStorage seam stores the bytes under a
        // document-media namespace and returns the storage key; only metadata + kind stays in the DB.
        String storageUri = objectStorage.store(file, STORAGE_NAMESPACE);

        DocumentMediaEntity media = new DocumentMediaEntity();
        media.setDocument(document);
        media.setFileName(resolveFileName(file));
        media.setContentType(file.getContentType());
        media.setSizeBytes(file.getSize());
        media.setStorageUri(storageUri);
        media.setKind(kind);
        media.setUploadedBy(requireCurrentUser());

        DocumentMediaEntity saved = mediaDao.save(media);
        return mapper.toDto(saved);
    }

    /**
     * The metadata of every media row of a document, newest first — backs the signing-tab media
     * listing (R7.1).
     *
     * @param documentId the owning document id
     * @return the document's media summaries
     */
    @Transactional(readOnly = true)
    public List<DocumentMediaSummaryDto> list(Long documentId) {
        requireDocument(documentId);
        return mapper.toMediaDtos(mediaDao.findByDocumentId(documentId));
    }

    /**
     * Delete a media row (and its object-storage bytes). Refuses to delete media that is the
     * completion evidence of a {@code SIGNED} signature with {@code 409 error.document.media.locked}
     * (R7.4) — the signed artifact and its evidence are immutable.
     *
     * @param mediaId the media id to delete
     */
    @Transactional
    public void delete(Long mediaId) {
        DocumentMediaEntity media = mediaDao.findById(mediaId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, mediaId));

        if (backsSignedSignature(media)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, MEDIA_LOCKED);
        }

        String storageUri = media.getStorageUri();
        mediaDao.delete(media);
        // Reclaim the object only if no DB row still references the key (replace-safe, R7.2).
        objectStorage.deleteIfOrphan(storageUri);
    }

    /**
     * Whether this media is the completion evidence of a {@code SIGNED} signature on the same
     * document — i.e. a {@code SIGNED} signature whose {@code evidenceUri} is this media's
     * {@code storageUri} (R7.4).
     */
    private boolean backsSignedSignature(DocumentMediaEntity media) {
        String storageUri = media.getStorageUri();
        if (storageUri == null || media.getDocument() == null || media.getDocument().getId() == null) {
            return false;
        }
        List<DocumentSignatureEntity> signatures = signatureDao.findByDocumentId(media.getDocument().getId());
        return signatures.stream()
                .anyMatch(s -> s.getStatus() == SignatureStatus.SIGNED
                        && storageUri.equals(s.getEvidenceUri()));
    }

    /**
     * Reject an empty upload, a disallowed content type, or an oversized file with {@code 400
     * error.document.media.invalid}, storing nothing (R7.3). A {@code null} kind is also rejected.
     */
    private void validate(MultipartFile file, DocumentMediaKind kind) {
        if (file == null || file.isEmpty() || kind == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, MEDIA_INVALID);
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, MEDIA_INVALID, contentType);
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, MEDIA_INVALID, file.getSize(), MAX_SIZE_BYTES);
        }
    }

    private SignableDocumentEntity requireDocument(Long documentId) {
        return documentDao.findById(documentId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, documentId));
    }

    private String resolveFileName(MultipartFile file) {
        String name = file.getOriginalFilename();
        return (name == null || name.isBlank()) ? "upload" : name;
    }

    /**
     * The authenticated caller as a {@link UserEntity} for the {@code uploadedBy} provenance,
     * resolved from the numeric principal (the JWT {@code sub} = user id), mirroring the actor
     * resolution used by {@code OfferService}/{@code OfferDiscountService}.
     */
    private UserEntity requireCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.unauthorized");
        }
        Long userId = parseUserId(auth.getName());
        if (userId == null) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.unauthorized");
        }
        return userDao.findById(userId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.unauthorized"));
    }

    private static Long parseUserId(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(name.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
