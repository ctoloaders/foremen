package com.foremen.service.payment.dto;

import java.math.BigDecimal;
import java.util.List;

import com.foremen.service.payment.ScheduleSource;
import com.foremen.service.payment.ScheduleStatus;

/**
 * FOR-06-07 (Requirement 1.4): the primary read model of a {@code PaymentSchedule} — the schedule
 * header, its ordered tranches, and the derived aggregates that let a caller see at a glance what the
 * client owes, what has been settled, and what remains.
 *
 * <p>The aggregates are <b>computed</b> (never stored): {@link #sumAmountGross} is {@code Σ tranche
 * amountGross}, {@link #sumPaidGross} is {@code Σ tranche paidGross}, and {@link #unpaidRemainder} is
 * {@code basisGross − sumPaidGross} (clamped at zero). {@link #unallocatedCredit} is the held surplus
 * carried past the last open tranche on an overpayment (R5.5). {@link #status} is the derived
 * {@code ScheduleStatus} ({@code SETTLED} iff every tranche is {@code PAID}, R1.6); {@link #source}
 * and {@link #status} are enums localized on the frontend via the {@code Enum_Label_I18n} convention,
 * never surfaced as the raw constant (R13.2, R13.3).
 *
 * @param id               the schedule id
 * @param projectId        the owning project id (one schedule per project, R1.1)
 * @param currency         the schedule currency, from the project (R1.2)
 * @param basisGross       the total being scheduled, seeded from the APPROVED offer (R1.2)
 * @param source           {@code GENERATED}/{@code MANUAL} per the creation path (R1.2)
 * @param status           the derived schedule status (R1.6)
 * @param tranches         the ordered tranches of this schedule (R2.1)
 * @param sumAmountGross   the sum of all tranche {@code amountGross} (R1.4)
 * @param sumPaidGross     the sum of all tranche {@code paidGross} (R1.4)
 * @param unpaidRemainder  {@code basisGross − sumPaidGross}, clamped at zero (R1.4)
 * @param unallocatedCredit the held surplus carried past the last open tranche (R5.5, R1.4)
 */
public record PaymentScheduleView(
        Long id,
        Long projectId,
        String currency,
        BigDecimal basisGross,
        ScheduleSource source,
        ScheduleStatus status,
        List<PaymentTrancheDto> tranches,
        BigDecimal sumAmountGross,
        BigDecimal sumPaidGross,
        BigDecimal unpaidRemainder,
        BigDecimal unallocatedCredit) {
}
