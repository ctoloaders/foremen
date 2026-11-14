package com.foremen.service.model;

import java.time.LocalDate;

import com.foremen.dao.model.RequestStatus;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-path service model for a purchase request (FOR-11-00 R11). {@code status}, {@code overdue}
 * and {@code positionCount} are derived on read and never persisted; no Money_Field is exposed.
 */
@Data
@NoArgsConstructor
public class PurchaseRequestServiceModel {
    private Long id;
    private Long projectId;
    private Long deliveryCategoryId;
    private String referenceNumber;
    private String referenceLink;
    private LocalDate expectedDeliveryDate;
    private Long assignedBuyerId;
    private String assignedBuyerName;
    private String notes;
    private RequestStatus status;
    private boolean overdue;
    private int positionCount;
}
