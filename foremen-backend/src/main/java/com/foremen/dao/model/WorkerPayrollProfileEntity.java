package com.foremen.dao.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-07-06 (Requirements 1.6, 9) — the worker's payroll profile: at most one row per worker
 * holding the opening balance the monthly balance chain starts from. Maps
 * {@code worker_payroll_profiles} (changeset {@code 196}); a worker without a row has an opening
 * balance of 0.
 */
@Entity
@Table(name = "worker_payroll_profiles")
@Getter
@Setter
@NoArgsConstructor
public class WorkerPayrollProfileEntity extends BaseEntity {

    /** The worker this profile belongs to, NOT NULL, unique. */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false, unique = true)
    private UserEntity worker;

    /** Signed opening balance (amount owed to the worker before the first tracked month). */
    @Column(name = "opening_balance", nullable = false, precision = 19, scale = 2)
    private BigDecimal openingBalance = BigDecimal.ZERO;
}
