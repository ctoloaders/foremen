package com.foremen.service.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.foremen.service.procurement.externalorder.ExternalOrderStatus;
import com.foremen.service.procurement.externalorder.PaidSource;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FOR-11-03 (R11.1, R11.5): header model of an external order with the derived totals. Carries
 * purchase-side money: exposed only to money-privileged roles (re-checked by the controller).
 */
@Data
@NoArgsConstructor
public class ExternalOrderServiceModel {

    private Long id;
    private Long projectId;
    private String projectName;
    private Long purchaseRequestId;
    private Long sellerId;
    private String sellerNameRU;
    private String sellerNamePL;
    private ExternalOrderStatus status;
    private String referenceNumber;
    private String orderLink;
    /** FOR-11-06 (R9): payment page URL; the model is exposed to money-privileged roles only. */
    private String paymentLink;
    private BigDecimal deliveryCostNet;
    private Long deliveryVatRateId;
    private LocalDate expectedShipDate;
    private LocalDate expectedDeliveryDate;
    private Long assignedBuyerId;
    private String assignedBuyerName;
    private String note;
    private String returnReason;
    private OffsetDateTime sentAt;
    private OffsetDateTime paidAt;
    private OffsetDateTime cancelledAt;
    private PaidSource paidSource;
    private Long paidById;
    private String paidNote;

    // --- derived, not persisted (R11.5) ---
    private int lineCount;
    private BigDecimal linesNet;
    private BigDecimal linesVat;
    private BigDecimal linesGross;
    private BigDecimal deliveryGross;
    private BigDecimal payableGross;
    private boolean overdue;
}
