package com.foremen.dao.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.foremen.service.clientpayment.ClientPaymentKind;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-11-06 (R1.1, R1.2; design 1.1 / 1.3): the base row of a unified client payment
 * ({@code client_payments}, changeset 247). Holds the common fields; the per-kind detail lives in
 * {@link PaymentTrancheEntity} (kind {@code WORKS}) or {@link ClientProformaEntity} (kind
 * {@code MATERIALS}), exactly one of which is non-null. The detail entities own the FK
 * ({@code client_payment_id}) and delegate their moved getters / setters here, so this row is the
 * single source of truth for the common fields.
 *
 * <p>{@link #status} is the per-kind stored status name ({@code TrancheStatus} or
 * {@code ProformaStatus}).
 */
@Entity
@Table(name = "client_payments")
@Getter
@Setter
@NoArgsConstructor
public class ClientPaymentEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ClientPaymentKind kind;

    @Column(nullable = false, length = 24)
    private String status;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "amount_gross", nullable = false, precision = 14, scale = 2)
    private BigDecimal amountGross = BigDecimal.ZERO;

    @Column(name = "paid_gross", nullable = false, precision = 14, scale = 2)
    private BigDecimal paidGross = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    private String currency = "PLN";

    @Column(length = 1000)
    private String note;

    /** Set when a MATERIALS payment is issued (R4.6). */
    @Column(name = "bank_account_snapshot", length = 34)
    private String bankAccountSnapshot;

    /** WORKS detail; null for MATERIALS. */
    @OneToOne(mappedBy = "payment", fetch = FetchType.LAZY)
    private PaymentTrancheEntity tranche;

    /** MATERIALS detail; null for WORKS. */
    @OneToOne(mappedBy = "payment", fetch = FetchType.LAZY)
    private ClientProformaEntity proforma;

    public ClientPaymentEntity(ClientPaymentKind kind, String status) {
        this.kind = kind;
        this.status = status;
    }
}
