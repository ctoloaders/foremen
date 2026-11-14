package com.foremen.controller.model;

import java.time.LocalDate;

import com.foremen.dao.model.RequestStatus;

/** Create response for a purchase request. */
public record PurchaseRequestCreateResponse(
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
