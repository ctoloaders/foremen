package com.foremen.service.room.media;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.foremen.dao.RoomMediaDao;
import com.foremen.service.image.ImageStorage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * FOR-05-02 (Requirements 10.4, 10.5): the room-read/room-delete touch points of room media that
 * {@code RoomService} needs, kept apart from {@link RoomMediaService} (which depends on
 * {@code RoomService}) so the two do not form a bean cycle.
 *
 * <ul>
 *   <li>{@link #countByRoomIds} — grouped media counts for a page of rooms in ONE query;</li>
 *   <li>{@link #collectStorageUris} + {@link #deleteObjectsAfterCommit} — on room delete the rows go
 *       with the room (FK ON DELETE CASCADE) and the stored objects are removed best-effort after the
 *       delete commits; anything missed is reclaimed by the image reconciliation job.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomMediaSupport {

    private final RoomMediaDao mediaDao;
    private final ImageStorage objectStorage;

    /** {@code roomId -> media count} for the given rooms (rooms without media are absent). */
    @Transactional(readOnly = true)
    public Map<Long, Integer> countByRoomIds(Collection<Long> roomIds) {
        Map<Long, Integer> counts = new HashMap<>();
        if (roomIds == null || roomIds.isEmpty()) {
            return counts;
        }
        for (Object[] row : mediaDao.countGroupedByRoomIds(roomIds)) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }
        return counts;
    }

    /** Storage uris of every media row of the given rooms; call BEFORE the rooms are deleted. */
    @Transactional(readOnly = true)
    public List<String> collectStorageUris(Collection<Long> roomIds) {
        if (roomIds == null || roomIds.isEmpty()) {
            return List.of();
        }
        return mediaDao.findStorageUrisByRoomIds(roomIds);
    }

    /**
     * Best-effort removal of the given objects once the surrounding transaction commits (immediately
     * when none is active). Never throws.
     */
    public void deleteObjectsAfterCommit(List<String> storageUris) {
        if (storageUris == null || storageUris.isEmpty()) {
            return;
        }
        List<String> keys = List.copyOf(storageUris);
        Runnable cleanup = () -> {
            for (String key : keys) {
                try {
                    objectStorage.deleteIfOrphan(key);
                } catch (RuntimeException e) {
                    log.warn("Best-effort delete of room media object {} failed: {}", key, e.getMessage());
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
        } else {
            cleanup.run();
        }
    }
}
