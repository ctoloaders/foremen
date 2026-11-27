package com.foremen.service.estimateai;

import java.math.BigDecimal;
import java.util.Set;

/**
 * FOR-05-04 — pure views of catalog and rooms handed to {@link PlanResolver} / {@link PlanValidator},
 * so the logic stays free of JPA entities.
 */
public final class PlanCatalog {

    private PlanCatalog() {
    }

    /**
     * @param price          catalog price (null or not positive means "no price")
     * @param percentageUnit true when the catalog unit is a percentage the engine cannot compute
     * @param roomTypeIds    room types the work is attached to; empty means unrestricted
     */
    public record CatalogWork(Long id, String code, String name, boolean active, BigDecimal price,
                              boolean percentageUnit, Set<Long> roomTypeIds) {
        public CatalogWork {
            roomTypeIds = roomTypeIds == null ? Set.of() : Set.copyOf(roomTypeIds);
        }
    }

    public record RoomFacts(Long id, Long roomTypeId, BigDecimal floorArea, BigDecimal wallArea) {
    }
}
