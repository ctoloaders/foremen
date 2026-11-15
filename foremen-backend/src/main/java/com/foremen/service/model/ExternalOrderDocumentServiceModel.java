package com.foremen.service.model;

import java.math.BigDecimal;

import com.foremen.dao.model.DocumentPaymentStatus;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FOR-11-03 (R11.3, R14): a supplier document attached to an external order. On write only
 * {@code procurementDocumentId} and {@code amount} are read; the rest is output.
 */
@Data
@NoArgsConstructor
public class ExternalOrderDocumentServiceModel {

    private Long id;
    private Long procurementDocumentId;
    /** Portion of the document total attributed to this order; null means the whole document. */
    private BigDecimal amount;

    // --- output only ---
    private String invoiceNumber;
    private String supplierName;
    private String supplierNip;
    private BigDecimal documentTotal;
    private DocumentPaymentStatus paymentStatus;
    /** R14.4: both NIPs present and different. */
    private boolean sellerMismatch;
}
