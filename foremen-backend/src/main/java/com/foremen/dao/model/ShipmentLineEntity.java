package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** FOR-11-04 (R2.2): a position (or part of it) on a shipment; unique per (shipment, position). */
@Entity
@Table(name = "shipment_lines")
@Getter
@Setter
@NoArgsConstructor
public class ShipmentLineEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false, updatable = false)
    private ShipmentEntity shipment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "position_id", nullable = false, updatable = false)
    private PurchasePositionEntity position;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;
}
