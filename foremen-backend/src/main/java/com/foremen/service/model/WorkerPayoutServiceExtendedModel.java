package com.foremen.service.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PayoutStatus;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FOR-06-11 (design §Components) — extended (write + single-read) service model for a
 * {@code WorkerPayout}, satisfying the {@code ProjectScopedService}/{@code AdminService} CRUD
 * contract {@code WorkerPayoutService} implements (entity-creation-rules step 4).
 *
 * <p>The payout lifecycle is driven by the dedicated endpoints {@code WorkerPayoutService} owns —
 * {@code create(CreatePayoutRequest)} (derives {@code isAdvance}, snapshots {@code suggestedAmount},
 * applies/links credit), {@code markPaid(id)}, and {@code delete(id)} — not by the generic
 * {@code AdminService} create/update. This model exists only to complete the generic CRUD contract
 * so the inherited by-id read surface compiles and stays project-scoped. On the write path the
 * {@code projectId} / {@code workerId} FKs and the author-supplied scalars are carried; the
 * server-derived fields ({@code suggestedAmount}, {@code advanceApplied}, {@code status},
 * {@code isAdvance}, {@code paidAt}) are set by the service, not copied from here.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerPayoutServiceExtendedModel {

    private Long id;

    private Long projectId;

    private Long workerId;
    /** The worker's localized display name (ru → pl → code); read path only (R8.1). */
    private String workerName;

    private LocalDate periodFrom;
    private LocalDate periodTo;

    /** The cash amount paid; defaults to {@code suggestedAmount}, overridable by the issuer (R1.4, R4.4). */
    private BigDecimal amount;
    /** The computed suggestion snapshot captured at creation; server-derived (R1.4). */
    private BigDecimal suggestedAmount;
    /** The advance-credit offset applied on a regular payout; server-derived (R5.6). */
    private BigDecimal advanceApplied;

    private PaymentChannel paymentChannel;
    /** The {@code PayoutStatus}; server-derived (R7). */
    private PayoutStatus status;

    /** {@code true} when {@code periodTo} is after the creation date; server-derived (R5.3). */
    private boolean isAdvance;

    /** When the payout was marked paid; read path only (R7.1). */
    private OffsetDateTime paidAt;

    private String note;
}
