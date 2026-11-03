package com.foremen.service.payroll.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.foremen.dao.model.WorkerPayrollLineEntity;

/**
 * FOR-07-06 (Requirement 8; design §Controllers and endpoints) — the read view of one additional
 * payroll line ({@link WorkerPayrollLineEntity}), returned by the lines CRUD, the hints endpoint and
 * the worker card's month blocks.
 *
 * @param id       the line id
 * @param workerId the worker's user id
 * @param month    the month, always its first day (R8.1)
 * @param name     the free-text name (no i18n variants, R8.2)
 * @param amount   the signed, non-zero amount; negative = deduction (R8.3)
 * @param note     optional note
 */
public record PayrollLineDto(Long id, Long workerId, LocalDate month, String name, BigDecimal amount, String note) {

    /** Flat projection of an entity; {@code null} in → {@code null} out. */
    public static PayrollLineDto from(WorkerPayrollLineEntity entity) {
        if (entity == null) {
            return null;
        }
        return new PayrollLineDto(
                entity.getId(),
                entity.getWorker() == null ? null : entity.getWorker().getId(),
                entity.getMonth(),
                entity.getName(),
                entity.getAmount(),
                entity.getNote());
    }
}
