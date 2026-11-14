package com.foremen.controller.model;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Create payload for a purchase request (FOR-11-00 R11). {@code assignedBuyerId} must be an active BUYER. */
public record PurchaseRequestCreateRequest(
        @NotNull Long projectId,
        Long deliveryCategoryId,
        @Size(max = 100) String referenceNumber,
        @Size(max = 1000) String referenceLink,
        LocalDate expectedDeliveryDate,
        Long assignedBuyerId,
        @Size(max = 2000) String notes
) {}
