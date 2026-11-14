package com.foremen.controller.model;

import java.time.LocalDate;

import com.foremen.dao.model.RequestStatus;

/** Update response for a purchase request. */
public record PurchaseRequestUpdateResponse(
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
