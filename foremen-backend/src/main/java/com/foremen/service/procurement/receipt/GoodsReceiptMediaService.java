package com.foremen.service.procurement.receipt;

import com.foremen.controller.model.GoodsReceiptMediaDto;
import com.foremen.dao.GoodsReceiptDao;
import com.foremen.dao.GoodsReceiptMediaDao;
import com.foremen.dao.model.GoodsReceiptEntity;
import com.foremen.dao.model.GoodsReceiptMediaEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.room.media.RoomMediaSupport;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * FOR-11-05 (R7, R13.3): photos of a receipt event, following the {@code RoomMediaService} pattern.
 * Declared type and magic bytes are validated by {@link GoodsReceiptRules#validateMedia} before anything is
 * stored; bytes are read through {@link ImageStorage#readObject} (503 when storage is off); objects are
 * removed after commit through {@code deleteIfOrphan}.
 */
@Service
public class GoodsReceiptMediaService {

    static final String STORAGE_NAMESPACE = "goods-receipts";
    private static final String NOT_FOUND = "error.entity.not.found";
    private static final String FORBIDDEN = "error.goodsReceipt.media.forbidden";

    private final GoodsReceiptDao receiptDao;
    private final GoodsReceiptMediaDao mediaDao;
    private final ImageStorage objectStorage;
    private final RoomMediaSupport support;
    private final ProcurementProjectAccess projectAccess;
    private final AuditLogDao auditLogDao;
    private final GoodsReceiptProperties properties;

    public GoodsReceiptMediaService(GoodsReceiptDao receiptDao, GoodsReceiptMediaDao mediaDao,
                                    ImageStorage objectStorage, RoomMediaSupport support,
                                    ProcurementProjectAccess projectAccess, AuditLogDao auditLogDao,
                                    GoodsReceiptProperties properties) {
        this.receiptDao = receiptDao;
        this.mediaDao = mediaDao;
        this.objectStorage = objectStorage;
        this.support = support;
        this.projectAccess = projectAccess;
        this.auditLogDao = auditLogDao;
        this.properties = properties;
    }

    /** Media of a receipt, oldest first. */
    @Transactional(readOnly = true)
    public List<GoodsReceiptMediaDto> list(Long receiptId) {
        requireReceipt(receiptId);
        return mediaDao.findByReceiptIdOrderById(receiptId).stream().map(GoodsReceiptMediaService::toDto).toList();
    }

    /** Validate everything first (a rejected request stores nothing), then store and register the files. */
    @Transactional
    public List<GoodsReceiptMediaDto> upload(Long receiptId, List<MultipartFile> files) {
        GoodsReceiptEntity receipt = requireReceipt(receiptId);
        requireWritable(receipt);
        List<MultipartFile> uploads = files == null ? List.of()
                : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (uploads.isEmpty()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.goodsReceipt.media.count");
        }
        List<GoodsReceiptRules.MediaMeta> metas = new ArrayList<>();
        for (MultipartFile file : uploads) {
            metas.add(new GoodsReceiptRules.MediaMeta(file.getContentType(), file.getSize(), head(file)));
        }
        GoodsReceiptRules.validateMedia(metas, (int) mediaDao.countByReceiptId(receiptId),
                properties.mediaLimits());

        List<GoodsReceiptMediaDto> result = new ArrayList<>();
        for (MultipartFile file : uploads) {
            String storageUri = objectStorage.storeObject(file, STORAGE_NAMESPACE);
            GoodsReceiptMediaEntity media = new GoodsReceiptMediaEntity();
            media.setReceipt(receipt);
            media.setFileName(sanitizeFileName(file.getOriginalFilename()));
            media.setContentType(file.getContentType().toLowerCase());
            media.setSizeBytes(file.getSize());
            media.setStorageUri(storageUri);
            GoodsReceiptMediaEntity saved = mediaDao.save(media);
            audit(saved, "CREATE");
            result.add(toDto(saved));
        }
        return result;
    }

    /** Metadata plus bytes of one photo. */
    @Transactional(readOnly = true)
    public Content content(Long receiptId, Long mediaId) {
        requireReceipt(receiptId);
        GoodsReceiptMediaEntity media = requireMedia(receiptId, mediaId);
        byte[] bytes = objectStorage.readObject(media.getStorageUri());
        if (bytes == null) {
            throw new ForemenApiException(HttpStatus.SERVICE_UNAVAILABLE, "error.image.storage.not.configured");
        }
        return new Content(media.getFileName(), media.getContentType(), bytes);
    }

    /** Delete a photo row and, after commit, its stored object when nothing else references it. */
    @Transactional
    public void delete(Long receiptId, Long mediaId) {
        GoodsReceiptEntity receipt = requireReceipt(receiptId);
        requireWritable(receipt);
        GoodsReceiptMediaEntity media = requireMedia(receiptId, mediaId);
        String storageUri = media.getStorageUri();
        audit(media, "DELETE");
        mediaDao.delete(media);
        support.deleteObjectsAfterCommit(List.of(storageUri));
    }

    /** Downloaded media payload. */
    public record Content(String fileName, String contentType, byte[] bytes) {
    }

    // --- internals ---

    /** Scope denial and a missing receipt both read as 404. */
    private GoodsReceiptEntity requireReceipt(Long receiptId) {
        GoodsReceiptEntity receipt = receiptDao.findById(receiptId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, receiptId));
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !hasAuthority(auth, "ADMIN")) {
            Long userId = parseUserId(auth);
            Set<Long> allowed = userId == null ? Set.of() : projectAccess.allowedProjectIds(userId);
            if (!allowed.contains(receipt.getProject().getId())) {
                throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, receiptId);
            }
        }
        return receipt;
    }

    /** Voided events are read-only; otherwise the author within the window, MANAGER and ADMIN may change photos. */
    private void requireWritable(GoodsReceiptEntity receipt) {
        if (receipt.getVoidedAt() != null) {
            throw new ForemenApiException(HttpStatus.CONFLICT, "error.goodsReceipt.alreadyVoided");
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Set<String> roles = new HashSet<>();
        Long userId = null;
        if (auth != null && auth.isAuthenticated()) {
            userId = parseUserId(auth);
            for (GrantedAuthority ga : auth.getAuthorities()) {
                String a = ga.getAuthority();
                roles.add(a != null && a.startsWith("ROLE_") ? a.substring(5) : a);
            }
        }
        Long authorId = receipt.getReceivedBy() == null ? null : receipt.getReceivedBy().getId();
        Instant occurredAt = receipt.getOccurredAt() == null ? null : receipt.getOccurredAt().toInstant();
        if (!GoodsReceiptRules.canVoid(roles, authorId, userId, occurredAt, Instant.now(),
                properties.voidWindow())) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
    }

    private GoodsReceiptMediaEntity requireMedia(Long receiptId, Long mediaId) {
        return mediaDao.findByIdAndReceiptId(mediaId, receiptId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, mediaId));
    }

    private static byte[] head(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(16);
        } catch (IOException e) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.goodsReceipt.media.type");
        }
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

    private static GoodsReceiptMediaDto toDto(GoodsReceiptMediaEntity m) {
        return new GoodsReceiptMediaDto(m.getId(), m.getFileName(), m.getSizeBytes(), m.getContentType());
    }

    /** Flat snapshot: ids and file metadata only, never the storage key. */
    private void audit(GoodsReceiptMediaEntity media, String operation) {
        String snapshot = "{\"receiptId\":" + media.getReceipt().getId()
                + ",\"fileName\":" + jsonString(media.getFileName())
                + ",\"contentType\":" + jsonString(media.getContentType())
                + ",\"sizeBytes\":" + media.getSizeBytes() + "}";
        AuditLogEntity entry = new AuditLogEntity();
        entry.setEntityClass(GoodsReceiptMediaEntity.class.getSimpleName());
        entry.setEntityId(media.getId());
        entry.setOperation(operation);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        entry.setPerformedBy(auth != null && auth.isAuthenticated() ? auth.getName() : "SYSTEM");
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

    private static boolean hasAuthority(Authentication auth, String code) {
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if (code.equals(a) || ("ROLE_" + code).equals(a)) {
                return true;
            }
        }
        return false;
    }

    private static Long parseUserId(Authentication auth) {
        try {
            return Long.parseLong(auth.getName().trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
