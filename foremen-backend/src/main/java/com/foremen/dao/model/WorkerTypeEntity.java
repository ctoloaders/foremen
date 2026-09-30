package com.foremen.dao.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-05-06 — worker hiring type (cost tier) dictionary. A managed reference dictionary
 * (ABAC resource {@code WORKER_TYPES}) whose rows drive the on-the-fly service cost model.
 */
@Entity
@Table(name = "worker_types")
@Getter
@Setter
@NoArgsConstructor
public class WorkerTypeEntity extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "name_ru", nullable = false)
    private String nameRU;

    @Column(name = "name_pl", nullable = false)
    private String namePL;

    /**
     * Tier percentage. For the base tier this is the SHARE of the offer price (e.g. 0.40).
     * For non-base tiers this is the UPLIFT ON BASE (e.g. 0.10, 0.265, 0.65).
     */
    @Column(name = "tier_pct", nullable = false, precision = 6, scale = 4)
    private BigDecimal tierPct;

    /** Exactly one row is the base tier (its {@code tierPct} is the offer share, others sit on top of it). */
    @Column(name = "is_base", nullable = false)
    private boolean base;

    @Column(name = "order_no", nullable = false)
    private Integer orderNo;

    @Column(nullable = false)
    private boolean active = true;
}
