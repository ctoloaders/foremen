package com.foremen.service.room.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

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
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.image.ImageStorage;

/**
 * FOR-05-02 (Requirements 10.2, 10.3, 10.6, 14.2, 14.3): list / upload / read / delete of the files
 * attached to a room. Bytes live in object storage ({@link ImageStorage}); only metadata + kind is
 * kept in {@code room_media}.
 *
 * <p><b>Project scope.</b> Every operation first resolves the room and asserts project access through
 * {@link RoomService#assertProjectAccess}, which answers {@code 404 error.entity.not.found} for a room
 * outside the caller's projects (no existence leak); a missing room is {@code 404} too. A media id
 * that does not belong to the addressed room is likewise {@code 404}.
 *
 * <p><b>Upload validation</b> (all files validated before any is stored, so a rejected request stores
 * nothing): count {@code error.room.media.count}; declared type JPEG/PNG/WEBP/PDF <em>and</em>
 * matching magic bytes {@code error.room.media.type}; size {@code error.room.media.size}.
 *
 * <p><b>Audit.</b> Upload and delete write a {@code RoomMediaEntity} CREATE / DELETE audit row.
 */
@Service
public class RoomMediaService {

    static final String TYPE_INVALID = "error.room.media.type";
    static final String SIZE_INVALID = "error.room.media.size";
    static final String COUNT_INVALID = "error.room.media.count";
    private static final String NOT_FOUND = "error.entity.not.found";

    /** Object-storage namespace of room media. */
    static final String STORAGE_NAMESPACE = "room-media";

    private static final Set<String> ALLOWED_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp", "application/pdf");

    private final RoomMediaDao mediaDao;
    private final RoomDao roomDao;
    private final RoomService roomService;
    private final UserDao userDao;
    private final ImageStorage objectStorage;
    private final RoomMediaSupport support;
    private final AuditLogDao auditLogDao;
    private final long maxSizeBytes;
    private final int maxFiles;

    public RoomMediaService(RoomMediaDao mediaDao,
                            RoomDao roomDao,
                            RoomService roomService,
                            UserDao userDao,
                            ImageStorage objectStorage,
                            RoomMediaSupport support,
                            AuditLogDao auditLogDao,
                            @Value("${foremen.room-media.max-file-size:20MB}") DataSize maxFileSize,
                            @Value("${foremen.room-media.max-files:20}") int maxFiles) {
        this.mediaDao = mediaDao;
        this.roomDao = roomDao;
        this.roomService = roomService;
        this.userDao = userDao;
        this.objectStorage = objectStorage;
        this.support = support;
        this.auditLogDao = auditLogDao;
        this.maxSizeBytes = maxFileSize.toBytes();
        this.maxFiles = maxFiles;
    }

    /** Media of a room, oldest first. */
    @Transactional(readOnly = true)
    public List<RoomMediaDto> list(Long roomId) {
        requireRoom(roomId);
        return mediaDao.findByRoomId(roomId).stream()
                .sorted(Comparator.comparing(RoomMediaEntity::getId))
                .map(RoomMediaService::toDto)
                .toList();
    }

    /** Validate and store uploaded files as {@code UPLOAD} media of the room. */
    @Transactional
    public List<RoomMediaDto> upload(Long roomId, List<MultipartFile> files) {
        RoomEntity room = requireRoom(roomId);
        List<MultipartFile> uploads = files == null ? List.of()
                : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (uploads.isEmpty()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, SIZE_INVALID, 0, maxSizeBytes);
        }
        if (mediaDao.countByRoomId(roomId) + uploads.size() > maxFiles) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, COUNT_INVALID, maxFiles);
        }
        for (MultipartFile file : uploads) {
            validate(file);
        }
        UserEntity uploader = requireCurrentUser();
        List<RoomMediaDto> result = new ArrayList<>();
        for (MultipartFile file : uploads) {
            String storageUri = objectStorage.storeObject(file, STORAGE_NAMESPACE);
            RoomMediaEntity media = new RoomMediaEntity();
            media.setRoom(room);
            media.setFileName(sanitizeFileName(file.getOriginalFilename()));
            media.setContentType(file.getContentType().toLowerCase());
            media.setSizeBytes(file.getSize());
            media.setStorageUri(storageUri);
            media.setKind(RoomMediaKind.UPLOAD);
            media.setUploadedBy(uploader);
            RoomMediaEntity saved = mediaDao.save(media);
            audit(saved, "CREATE");
            result.add(toDto(saved));
        }
        return result;
    }

    /** Metadata + bytes of one media file. */
    @Transactional(readOnly = true)
    public Content content(Long roomId, Long mediaId) {
        requireRoom(roomId);
        RoomMediaEntity media = requireMedia(roomId, mediaId);
        byte[] bytes = objectStorage.readObject(media.getStorageUri());
        if (bytes == null) {
            throw new ForemenApiException(HttpStatus.SERVICE_UNAVAILABLE, "error.image.storage.not.configured");
        }
        return new Content(media.getFileName(), media.getContentType(), bytes);
    }

    /** Delete a media row and (best-effort, after commit) its stored object. */
    @Transactional
    public void delete(Long roomId, Long mediaId) {
        requireRoom(roomId);
        RoomMediaEntity media = requireMedia(roomId, mediaId);
        String storageUri = media.getStorageUri();
        audit(media, "DELETE");
        mediaDao.delete(media);
        support.deleteObjectsAfterCommit(List.of(storageUri));
    }

    /** Downloaded media payload. */
    public record Content(String fileName, String contentType, byte[] bytes) {
    }

    // --- internals ---

    private RoomEntity requireRoom(Long roomId) {
        RoomEntity room = roomDao.findById(roomId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, roomId));
        roomService.assertProjectAccess(roomId);
        return room;
    }

    private RoomMediaEntity requireMedia(Long roomId, Long mediaId) {
        RoomMediaEntity media = mediaDao.findById(mediaId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, mediaId));
        if (media.getRoom() == null || !roomId.equals(media.getRoom().getId())) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, mediaId);
        }
        return media;
    }

    private void validate(MultipartFile file) {
        String declared = file.getContentType() == null ? null : file.getContentType().toLowerCase();
        if (declared == null || !ALLOWED_TYPES.contains(declared)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, TYPE_INVALID, declared);
        }
        if (file.getSize() > maxSizeBytes) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, SIZE_INVALID, file.getSize(), maxSizeBytes);
        }
        byte[] head;
        try (java.io.InputStream in = file.getInputStream()) {
            head = in.readNBytes(16);
        } catch (IOException e) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, TYPE_INVALID, declared);
        }
        if (!declared.equals(sniff(head))) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, TYPE_INVALID, declared);
        }
    }

    /** Content type implied by the leading magic bytes, or {@code null} when not one of the allowed. */
    static String sniff(byte[] h) {
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G'
                && h[4] == 0x0D && h[5] == 0x0A && h[6] == 0x1A && h[7] == 0x0A) {
            return "image/png";
        }
        if (h.length >= 12 && "RIFF".equals(new String(h, 0, 4, StandardCharsets.US_ASCII))
                && "WEBP".equals(new String(h, 8, 4, StandardCharsets.US_ASCII))) {
            return "image/webp";
        }
        if (h.length >= 5 && "%PDF-".equals(new String(Arrays.copyOf(h, 5), StandardCharsets.US_ASCII))) {
            return "application/pdf";
        }
        return null;
    }

    /** Client file name is metadata only: strip any path and control characters, cap the length. */
    static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "upload";
        }
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").trim();
        if (base.isEmpty()) {
            return "upload";
        }
        return base.length() > 255 ? base.substring(base.length() - 255) : base;
    }

    private static RoomMediaDto toDto(RoomMediaEntity m) {
        return new RoomMediaDto(m.getId(), m.getRoom().getId(), m.getFileName(), m.getContentType(),
                m.getSizeBytes(), m.getKind(), m.getCreatedDate());
    }

    private void audit(RoomMediaEntity media, String operation) {
        String snapshot = "{\"roomId\":" + media.getRoom().getId()
                + ",\"fileName\":" + jsonString(media.getFileName())
                + ",\"contentType\":" + jsonString(media.getContentType())
                + ",\"sizeBytes\":" + media.getSizeBytes()
                + ",\"kind\":\"" + media.getKind() + "\"}";
        AuditLogEntity entry = new AuditLogEntity();
        entry.setEntityClass(RoomMediaEntity.class.getSimpleName());
        entry.setEntityId(media.getId());
        entry.setOperation(operation);
        entry.setPerformedBy(currentUserName());
        entry.setPerformedAt(LocalDateTime.now());
        if ("DELETE".equals(operation)) {
            entry.setSnapshotBefore(snapshot);
        } else {
            entry.setSnapshotAfter(snapshot);
        }
        auditLogDao.save(entry);
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String currentUserName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : "SYSTEM";
    }

    private UserEntity requireCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.unauthorized");
        }
        Long userId;
        try {
            userId = Long.valueOf(auth.getName().trim());
        } catch (RuntimeException e) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.unauthorized");
        }
        return userDao.findById(userId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.unauthorized"));
    }
}
