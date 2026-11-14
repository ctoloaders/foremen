package com.foremen.controller.model;

import java.time.LocalDate;

import jakarta.validation.constraints.Size;

/** Update payload for a purchase request; the project is immutable. Null fields are left unchanged. */
public record PurchaseRequestUpdateRequest(
        Long deliveryCategoryId,
        @Size(max = 100) String referenceNumber,
        @Size(max = 1000) String referenceLink,
        LocalDate expectedDeliveryDate,
        Long assignedBuyerId,
        @Size(max = 2000) String notes
) {}
