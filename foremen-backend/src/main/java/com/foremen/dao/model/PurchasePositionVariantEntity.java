package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * FOR-11-00 (R21.1): a retired material option of a purchase position
 * ({@code purchase_position_variants}). The retirement time and user are {@code createdDate} /
 * {@code createdBy}. Rows are deleted with the position (FK {@code ON DELETE CASCADE}).
 */
@Entity
@Table(name = "purchase_position_variants")
@Getter
@Setter
@NoArgsConstructor
public class PurchasePositionVariantEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    @JoinColumn(name = "position_id", nullable = false)
    private PurchasePositionEntity position;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false)
    private FinishingMaterialEntity material;

    @Column(name = "unit_sale_price", precision = 14, scale = 2)
    private BigDecimal unitSalePrice;

    @Column(name = "unit_planned_purchase_price", precision = 14, scale = 2)
    private BigDecimal unitPlannedPurchasePrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VariantReason reason;

    @Column(length = 500)
    private String note;
}
