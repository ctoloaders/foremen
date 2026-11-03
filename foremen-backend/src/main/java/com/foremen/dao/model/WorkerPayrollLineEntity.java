package com.foremen.dao.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-07-06 (Requirements 1.5, 8) — an additional payroll line: a free-text, signed one-off
 * payment or deduction for a worker in a month (e.g. a Multisport reimbursement, a supervision
 * supplement, a deduction). Maps {@code worker_payroll_lines} (changeset {@code 195}).
 *
 * <p>{@link #month} is always the first day of a month (normalized by the service); {@link #amount}
 * is non-zero (DB CHECK {@code amount <> 0}), negative meaning a deduction.
 */
@Entity
@Table(name = "worker_payroll_lines")
@Getter
@Setter
@NoArgsConstructor
public class WorkerPayrollLineEntity extends BaseEntity {

    /** The worker the line belongs to, NOT NULL. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    private UserEntity worker;

    /** The month of the line, stored as the first day of that month. */
    @Column(name = "month", nullable = false)
    private LocalDate month;

    /** Free-text line name, up to 255 characters. */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Signed, non-zero amount; negative = deduction. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** Optional free-form note. */
    @Column(name = "note", columnDefinition = "text")
    private String note;
}
