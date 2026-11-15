package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** FOR-11-03 (R11.2): a position (or part of it) on an external order; unique per (order, position). */
@Entity
@Table(name = "external_order_lines")
@Getter
@Setter
@NoArgsConstructor
public class ExternalOrderLineEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "external_order_id", nullable = false)
    private ExternalOrderEntity externalOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "position_id", nullable = false)
    private PurchasePositionEntity position;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit_purchase_net", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPurchaseNet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_vat_rate_id")
    private VatRateEntity purchaseVatRate;

    @Column(name = "delivery_cost_share", nullable = false, precision = 14, scale = 2)
    private BigDecimal deliveryCostShare = BigDecimal.ZERO;

    @Column(name = "above_ceiling_ack", nullable = false)
    private boolean aboveCeilingAcknowledged;
}
