package com.foremen.dao.model;

import com.foremen.service.clientpayment.ClientPaymentKind;
import com.foremen.service.procurement.proforma.ProformaStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * FOR-11-03 (R2.1): our advance-payment document to the client ({@code client_proformas}).
 *
 * <p>FOR-11-06 (design 1.3): status, due date, gross total (as {@code amountGross}), paid total,
 * currency and note live on the unified base row {@link #payment} ({@code client_payments}); the
 * matching getters / setters delegate to it.
 */
@Entity
@Table(name = "client_proformas")
@Getter
@Setter
@NoArgsConstructor
public class ClientProformaEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private ProjectEntity project;

    /** Unified base row (FOR-11-06): holds status, due date, gross amounts, currency and note. */
    @OneToOne(optional = false, fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "client_payment_id", nullable = false)
    private ClientPaymentEntity payment = newPayment();

    /** Null until issued; unique when present. */
    @Column(length = 32)
    private String number;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "total_net", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalNet = BigDecimal.ZERO;

    @Column(name = "total_vat", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalVat = BigDecimal.ZERO;

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

    private ClientPaymentEntity newPayment() {
        ClientPaymentEntity p = new ClientPaymentEntity(ClientPaymentKind.MATERIALS, ProformaStatus.DRAFT.name());
        p.setProforma(this);
        return p;
    }

    /** Also keeps the base row's project in step. */
    public void setProject(ProjectEntity project) {
        this.project = project;
        if (payment != null) {
            payment.setProject(project);
        }
    }

    public ProformaStatus getStatus() {
        return ProformaStatus.valueOf(payment.getStatus());
    }

    public void setStatus(ProformaStatus status) {
        payment.setStatus(status.name());
    }

    public String getCurrency() {
        return payment.getCurrency();
    }

    public void setCurrency(String currency) {
        payment.setCurrency(currency);
    }

    public LocalDate getDueDate() {
        return payment.getDueDate();
    }

    public void setDueDate(LocalDate dueDate) {
        payment.setDueDate(dueDate);
    }

    public String getNote() {
        return payment.getNote();
    }

    public void setNote(String note) {
        payment.setNote(note);
    }

    /** The proforma gross total; stored as the base row's {@code amountGross}. */
    public BigDecimal getTotalGross() {
        return payment.getAmountGross();
    }

    public void setTotalGross(BigDecimal totalGross) {
        payment.setAmountGross(totalGross);
    }

    public BigDecimal getPaidGross() {
        return payment.getPaidGross();
    }

    public void setPaidGross(BigDecimal paidGross) {
        payment.setPaidGross(paidGross);
    }
}
