package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;

/**
 * FOR-11-02 (R2.2): an option of an approval round ({@code position_approval_options}).
 * {@code internalUnitPurchasePrice} is admin-only and must never reach a client type.
 */
@Entity
@Table(name = "position_approval_options")
@Getter
@Setter
@NoArgsConstructor
public class PositionApprovalOptionEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    @JoinColumn(name = "round_id", nullable = false)
    private PositionApprovalRoundEntity round;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false)
    private FinishingMaterialEntity material;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "unit_sale_price", precision = 14, scale = 2)
    private BigDecimal unitSalePrice;

    @Column(name = "internal_unit_purchase_price", precision = 14, scale = 2)
    private BigDecimal internalUnitPurchasePrice;
}
