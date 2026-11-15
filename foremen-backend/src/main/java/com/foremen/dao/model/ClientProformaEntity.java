package com.foremen.dao.model;

import com.foremen.service.procurement.proforma.ProformaStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** FOR-11-03 (R2.1): our advance-payment document to the client ({@code client_proformas}). */
@Entity
@Table(name = "client_proformas")
@Getter
@Setter
@NoArgsConstructor
public class ClientProformaEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private ProjectEntity project;

    /** Null until issued; unique when present. */
    @Column(length = 32)
    private String number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProformaStatus status = ProformaStatus.DRAFT;

    @Column(nullable = false, length = 3)
    private String currency = "PLN";

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(length = 1000)
    private String note;

    @Column(name = "total_net", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalNet = BigDecimal.ZERO;

    @Column(name = "total_vat", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalVat = BigDecimal.ZERO;

    @Column(name = "total_gross", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalGross = BigDecimal.ZERO;

    @Column(name = "paid_gross", nullable = false, precision = 14, scale = 2)
    private BigDecimal paidGross = BigDecimal.ZERO;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "released_at")
    private OffsetDateTime releasedAt;

    @Column(name = "pdf_object_key", length = 500)
    private String pdfObjectKey;

    /** {@code pl} or {@code ru}. */
    @Column(name = "pdf_language", length = 2)
    private String pdfLanguage;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issued_by")
    private UserEntity issuedBy;
}
