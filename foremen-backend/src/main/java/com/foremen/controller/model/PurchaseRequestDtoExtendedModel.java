package com.foremen.controller.model;

import java.time.LocalDate;

import com.foremen.dao.model.RequestStatus;

/** Edit-form DTO for a purchase request (same shape as the list DTO). */
public record PurchaseRequestDtoExtendedModel(
        Long id,
        Long projectId,
        Long deliveryCategoryId,
        String referenceNumber,
        String referenceLink,
        LocalDate expectedDeliveryDate,
        Long assignedBuyerId,
        String assignedBuyerName,
        String notes,
        RequestStatus status,
        boolean overdue,
        int positionCount
) {}
