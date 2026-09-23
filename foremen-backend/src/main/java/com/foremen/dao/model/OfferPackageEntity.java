package com.foremen.dao.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "offer_packages")
@Getter
@Setter
@NoArgsConstructor
public class OfferPackageEntity extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "order_no", nullable = false)
    private Integer orderNo;

    @Column(name = "name_ru", nullable = false)
    private String nameRU;

    @Column(name = "name_pl", nullable = false)
    private String namePL;

    @Column(nullable = false)
    private boolean active = true;

    /**
     * Denormalized package zł/m² cache (FOR-05-04-UI): the last value computed by the
     * package-save recompute (Σ group /m² for this package). Nullable — {@code null} means "not
     * yet computed/saved". Written by the package-save flow; exposed read-only on the DTOs.
     */
    @Column(name = "zl_m2")
    private BigDecimal zlM2;
}
