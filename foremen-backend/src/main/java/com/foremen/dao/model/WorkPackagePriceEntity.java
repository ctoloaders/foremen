package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "work_package_prices",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_work_package_prices_work_offer",
                columnNames = {"work_price_id", "offer_package_id"}))
@Getter
@Setter
@NoArgsConstructor
public class WorkPackagePriceEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_price_id", nullable = false)
    private WorkPriceEntity workPrice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offer_package_id", nullable = false)
    private OfferPackageEntity offerPackage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "currency_id", nullable = false)
    private CurrencyEntity currency;

    @Column(name = "net_price", nullable = false)
    private BigDecimal netPrice;
}
