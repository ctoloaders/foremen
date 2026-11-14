package com.foremen.controller.model;

import java.time.LocalDate;

import com.foremen.dao.model.RequestStatus;

/** List/read/response DTO for a purchase request; derived {@code status}, {@code overdue}, {@code positionCount}. */
public record PurchaseRequestDtoModel(
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
