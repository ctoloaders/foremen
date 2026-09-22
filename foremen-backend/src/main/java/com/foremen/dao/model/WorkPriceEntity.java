package com.foremen.dao.model;

import java.math.BigDecimal;

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
 * A work item's single catalog price (FOR-05-04, Requirement 1): {@code (workItem, currency,
 * netPrice)}, one row per {@link WorkItemEntity} — no {@code offerPackage} dimension and no
 * {@code validFrom}/{@code validTo} interval (temporal resolution stays owned by
 * {@code ProjectServicePrice}, FOR-05-14). Replaces the FOR-04-12b per-package shape that used to
 * live on the now-removed {@code WorkPackagePriceEntity} collection (removed by FOR-05-04 task
 * 11.2).
 */
@Entity
@Table(name = "work_prices")
@Getter
@Setter
@NoArgsConstructor
public class WorkPriceEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_item_id", nullable = false, unique = true)
    private WorkItemEntity workItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "currency_id", nullable = false)
    private CurrencyEntity currency;

    @Column(name = "net_price", nullable = false)
    private BigDecimal netPrice;
}
