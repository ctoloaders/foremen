package com.foremen.service.estimateai.chat;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * FOR-05-04 (design B8) — read-only catalog/room facade of the offer chat tools. Keeps the tools free
 * of JPA entities; the Spring implementation is {@link JpaChatCatalog}.
 */
public interface ChatCatalog {

    /**
     * @param roomTypeIds room types the work is attached to; empty means unrestricted
     */
    record WorkInfo(Long id, String code, String nameRu, String namePl, String unit, boolean active,
                    boolean hasPrice, Long categoryId, String categoryCode, Set<Long> roomTypeIds) {
        public WorkInfo {
            roomTypeIds = roomTypeIds == null ? Set.of() : Set.copyOf(roomTypeIds);
        }
    }

    record RoomInfo(Long id, String label, Long roomTypeId) {
    }

    /** Catalog search by code or name (RU/PL), optionally within one category; at most {@code limit} hits. */
    List<WorkInfo> search(String query, String categoryCode, int limit);

    Optional<WorkInfo> findByCode(String code);

    Optional<WorkInfo> findById(Long id);

    Optional<Long> categoryIdByCode(String categoryCode);

    /** Work code per work item id, for the ids that exist. */
    Map<Long, String> workCodes(Collection<Long> workItemIds);

    List<RoomInfo> projectRooms(Long projectId);
}
