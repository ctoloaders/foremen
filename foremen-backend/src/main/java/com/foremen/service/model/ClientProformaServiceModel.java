package com.foremen.service.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.foremen.service.procurement.proforma.ProformaStatus;

import lombok.Data;
import lombok.NoArgsConstructor;

/** FOR-11-03 (R2.1): list model of a client proforma (staff view, no lines). */
@Data
@NoArgsConstructor
public class ClientProformaServiceModel {
    private Long id;
    private Long projectId;
    private String projectName;
    private String number;
    private ProformaStatus status;
    private String currency;
    private LocalDate issueDate;
    private LocalDate dueDate;
    private String note;
    private BigDecimal totalNet;
    private BigDecimal totalVat;
    private BigDecimal totalGross;
    private BigDecimal paidGross;
    private OffsetDateTime paidAt;
    private OffsetDateTime releasedAt;
    private String pdfLanguage;
    /** True once the PDF is stored (the object key itself is never exposed). */
    private boolean hasPdf;
    private OffsetDateTime cancelledAt;
    private String cancelReason;
    private Long issuedById;
    /** Derived, not persisted (R2.6). */
    private boolean overdue;
}
