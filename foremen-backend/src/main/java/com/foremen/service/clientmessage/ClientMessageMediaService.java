package com.foremen.service.clientmessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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

import lombok.RequiredArgsConstructor;

/**
 * FOR-06-12 (Requirements 4.1–4.5): the media-attachment service for client-communication messages,
 * mirroring {@link com.foremen.service.document.DocumentMediaService} but with its <strong>own</strong>
 * content-type allow-list and <strong>per-kind</strong> size caps.
 *
 * <p>The bytes live in the object store (the shared {@link ImageStorage} seam, FOR-04-17) under the
 * {@code client-messages} namespace via {@link ImageStorage#storeObject(MultipartFile, String)};
 * only the metadata + the derived {@link MediaKind} are persisted as a {@link ClientMessageMediaEntity}
 * child of its message (R4.1). Because {@code storeObject} does not re-impose the image-only
 * allow-list, this service validates the content type and size itself before storing (R4.2, R4.3).
 *
 * <p><b>Allow-list (R4.2).</b> images ({@code image/png}, {@code image/jpeg}, {@code image/webp}),
 * documents ({@code application/pdf}) and video ({@code video/mp4}, {@code video/webm},
 * {@code video/quicktime}); any other type is rejected 400 {@code error.clientMessage.media.invalid},
 * storing nothing.
 *
 * <p><b>Per-kind size caps (R4.3).</b> 10&nbsp;MB for an image or document, 100&nbsp;MB for a video;
 * an oversized file is rejected 400 {@code error.clientMessage.media.tooLarge} naming the size and the
 * limit, storing nothing.
 *
 * <p><b>Signed download URLs (R4.4).</b> media objects live in a PRIVATE bucket and are served only
 * via short-lived V4 signed GET URLs (never a public CDN link), mirroring
 * {@code SignableDocumentService}/{@code ProcurementDocumentService}. The service resolves a per-media
 * id → URL map the thread mapper consumes to populate {@link ClientMessageMediaSummaryDto#downloadUrl()}.
 *
 * <p><b>Orphan reclaim (R4).</b> on delete the object is reclaimed via
 * {@link ImageStorage#deleteIfOrphan(String)}, mirroring {@code DocumentMediaService}.
 *
 * <p>This service does <strong>not</strong> resolve the current caller — the uploader is supplied by
 * {@code ClientMessageService.post(...)} (the already-resolved message author), so attaching media is
 * part of the post transaction and the provenance is the message author.
 */
@Service
@RequiredArgsConstructor
public class ClientMessageMediaService {

    /** 400 for an empty / disallowed-type upload (R4.2). */
    private static final String MEDIA_INVALID = "error.clientMessage.media.invalid";

    /** 400 for an upload exceeding the per-kind size cap (R4.3). */
    private static final String MEDIA_TOO_LARGE = "error.clientMessage.media.tooLarge";

    /** Namespace under which client-message media objects are stored in object storage (R4.1). */
    static final String NAMESPACE = "client-messages";

    /** Image + document content types; capped at {@link #MAX_IMAGE_DOC_BYTES} (R4.2, R4.3). */
    static final Set<String> IMAGE_DOC_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/webp",
            "application/pdf");

    /** Video content types; capped at {@link #MAX_VIDEO_BYTES} (R4.2, R4.3). */
    static final Set<String> VIDEO_TYPES = Set.of(
            "video/mp4",
            "video/webm",
            "video/quicktime");

    /** Per-kind cap for an image or document (R4.3). */
    static final long MAX_IMAGE_DOC_BYTES = DataSize.ofMegabytes(10).toBytes();

    /** Per-kind cap for a video (R4.3). */
    static final long MAX_VIDEO_BYTES = DataSize.ofMegabytes(100).toBytes();

    private final ClientMessageMediaDao mediaDao;
    private final ImageStorage objectStorage;
    private final ImageStorageProperties imageStorageProperties;

    /**
     * Validate every file against the own allow-list + per-kind size cap, store each in object
     * storage under the {@code client-messages} namespace, and attach a persisted
     * {@link ClientMessageMediaEntity} to the message. Called by {@code ClientMessageService.post}
     * inside the post transaction (R4.1).
     *
     * <p>A {@code null}/empty file list attaches nothing (a text-only message). Validation is
     * all-or-nothing per file: a single invalid/oversized file aborts with the matching 400 before
     * any byte is stored (the list is validated as it is processed; callers pass the whole batch so
     * a reject surfaces the offending file).
     *
     * @param message  the owning message (already persisted so children can reference it)
     * @param files    the uploaded files; may be {@code null}/empty for a text-only message
     * @param uploader the resolved uploader (the message author), for {@code uploadedBy} provenance
     * @return the persisted media rows (empty when no files were supplied)
     */
    public List<ClientMessageMediaEntity> attachAll(ClientMessageEntity message,
                                                    List<MultipartFile> files,
                                                    UserEntity uploader) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        return files.stream()
                .map(file -> store(message, file, uploader))
                .toList();
    }

    /**
     * Validate a single file against the own allow-list + the size cap for its derived kind, store
     * the bytes in object storage, and persist the attachment metadata as a child of the message
     * (R4.1, R4.2, R4.3).
     *
     * @param message  the owning message
     * @param file     the uploaded file; must be a permitted type within the per-kind cap
     * @param uploader the resolved uploader (the message author)
     * @return the persisted media row
     */
    public ClientMessageMediaEntity store(ClientMessageEntity message,
                                          MultipartFile file,
                                          UserEntity uploader) {
        MediaKind kind = validate(file);

        // FOR-04-17 object storage (R4.1): storeObject does NOT re-impose the image-only allow-list,
        // so this service's own type/size validation above is authoritative.
        String storageUri = objectStorage.storeObject(file, NAMESPACE);

        ClientMessageMediaEntity media = new ClientMessageMediaEntity();
        media.setMessage(message);
        media.setFileName(resolveFileName(file));
        media.setContentType(file.getContentType());
        media.setSizeBytes(file.getSize());
        media.setStorageUri(storageUri);
        media.setKind(kind);
        media.setUploadedBy(uploader);

        return mediaDao.save(media);
    }

    /**
     * Delete a media row and reclaim its object-storage bytes if no DB row still references the key
     * (replace-safe), mirroring {@code DocumentMediaService.delete} (R4).
     *
     * @param media the media row to delete
     */
    public void delete(ClientMessageMediaEntity media) {
        if (media == null) {
            return;
        }
        String storageUri = media.getStorageUri();
        mediaDao.delete(media);
        objectStorage.deleteIfOrphan(storageUri);
    }

    /**
     * Resolve a short-lived signed download URL for one media attachment (R4.4). A {@code null}
     * media / blank object key yields {@code null}; the disabled storage fallback also returns
     * {@code null}.
     *
     * @param media the media row
     * @return the per-media signed download URL, or {@code null} when none can be built
     */
    public String downloadUrl(ClientMessageMediaEntity media) {
        if (media == null) {
            return null;
        }
        String objectKey = media.getStorageUri();
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        return objectStorage.signedDownloadUrl(objectKey, imageStorageProperties.signedUrlTtlSeconds());
    }

    /**
     * Build the media id → signed download URL map for a single message, consumed by
     * {@code ClientMessageDtoMapper} to populate each {@link ClientMessageMediaSummaryDto#downloadUrl()}
     * (R4.4). A media whose URL cannot be built is omitted (the mapper then leaves its URL
     * {@code null}).
     *
     * @param message the message whose attachments to resolve
     * @return media id → signed URL (never {@code null}; empty when the message has no media)
     */
    public Map<Long, String> signedUrls(ClientMessageEntity message) {
        Map<Long, String> urls = new LinkedHashMap<>();
        if (message == null || message.getMedia() == null) {
            return urls;
        }
        for (ClientMessageMediaEntity media : message.getMedia()) {
            putIfResolvable(urls, media);
        }
        return urls;
    }

    /**
     * Build the media id → signed download URL map across every message of a thread, consumed by
     * {@code ClientMessageDtoMapper.toThreadView} (R4.4).
     *
     * @param messages the thread's messages
     * @return media id → signed URL across all messages (never {@code null})
     */
    public Map<Long, String> signedUrls(List<ClientMessageEntity> messages) {
        Map<Long, String> urls = new LinkedHashMap<>();
        if (messages == null) {
            return urls;
        }
        for (ClientMessageEntity message : messages) {
            if (message == null || message.getMedia() == null) {
                continue;
            }
            for (ClientMessageMediaEntity media : message.getMedia()) {
                putIfResolvable(urls, media);
            }
        }
        return urls;
    }

    private void putIfResolvable(Map<Long, String> urls, ClientMessageMediaEntity media) {
        if (media == null || media.getId() == null) {
            return;
        }
        String url = downloadUrl(media);
        if (url != null) {
            urls.put(media.getId(), url);
        }
    }

    /**
     * Validate the upload and return its {@link MediaKind}. Rejects an empty upload or a disallowed
     * content type with 400 {@code error.clientMessage.media.invalid} (R4.2), and a file over the
     * per-kind cap with 400 {@code error.clientMessage.media.tooLarge} naming the size and the limit
     * (R4.3). Stores nothing on rejection.
     */
    private MediaKind validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, MEDIA_INVALID);
        }
        String contentType = file.getContentType();
        String normalized = contentType == null ? null : contentType.toLowerCase();
        if (normalized == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, MEDIA_INVALID, contentType);
        }

        MediaKind kind;
        long cap;
        if (IMAGE_DOC_TYPES.contains(normalized)) {
            kind = normalized.equals("application/pdf") ? MediaKind.DOCUMENT : MediaKind.IMAGE;
            cap = MAX_IMAGE_DOC_BYTES;
        } else if (VIDEO_TYPES.contains(normalized)) {
            kind = MediaKind.VIDEO;
            cap = MAX_VIDEO_BYTES;
        } else {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, MEDIA_INVALID, contentType);
        }

        if (file.getSize() > cap) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, MEDIA_TOO_LARGE, file.getSize(), cap);
        }
        return kind;
    }

    private String resolveFileName(MultipartFile file) {
        String name = file.getOriginalFilename();
        return (name == null || name.isBlank()) ? "upload" : name;
    }
}
