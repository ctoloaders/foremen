package com.foremen.controller.model;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/purchase-requests/from-positions} (FOR-11-01 R7.1). Emptiness and
 * single-project checks are done by the service so they return the dedicated 400 keys.
 */
public record FromPositionsRequest(
        List<Long> positionIds,
        Long deliveryCategoryId,
        @Size(max = 100) String referenceNumber,
        @Size(max = 1000) String referenceLink,
        LocalDate expectedDeliveryDate,
        Long assignedBuyerId,
        @Size(max = 2000) String notes
) {}
