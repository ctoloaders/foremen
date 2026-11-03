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
 * FOR-06-11 (design §Components) — read-path service model for a {@code WorkerPayout}, satisfying
 * the company-wide {@code AdminService} CRUD contract {@code WorkerPayoutService} implements
 * (FOR-07-06: no project scoping).
 *
 * <p>The payroll UI does not consume the generic {@code AdminService} list/read projection — it
 * reads {@code WorkerPayoutService.list(PayoutFilter)} and the payroll card. This model exists only to complete the generic CRUD contract
 * (so the inherited {@code find}/{@code findById}/paging surface compiles and stays available); it
 * is a flat projection of the entity carrying the FK ids, the localized {@code workerName}
 * ({@code ru → pl → code} fallback, R8.1), and the stored scalars. The {@code paymentChannel} /
 * {@code status} / {@code isAdvance} classification is copied through for the frontend to localize
 * (R2.3, R8.2).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerPayoutServiceModel {

    private Long id;

    private Long workerId;
    /** The worker's localized display name (ru → pl → code), resolved in the service mapper (R8.1). */
    private String workerName;

    /** FOR-07-06: the payout date (payouts are worker-wide, no project). */
    private LocalDate payoutDate;

    private LocalDate periodFrom;
    private LocalDate periodTo;

    /** The cash amount paid; defaults to {@code suggestedAmount}, overridable by the issuer (R1.4, R4.4). */
    private BigDecimal amount;
    /** The computed suggestion snapshot captured at creation; immutable afterwards (R1.4). */
    private BigDecimal suggestedAmount;
    /** The advance-credit offset applied on a regular payout, else {@code 0} (R5.6). */
    private BigDecimal advanceApplied;

    private PaymentChannel paymentChannel;
    private PayoutStatus status;

    /** {@code true} when {@code periodTo} is after the creation date; derived, never a user flag (R5.3). */
    private boolean isAdvance;

    /** When the payout was marked paid; {@code null} while {@code PLANNED} (R7.1). */
    private OffsetDateTime paidAt;

    private String note;
}
