package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** FOR-11-03 (R2.2): a frozen proforma line; at most one active line per position (R2.4). */
@Entity
@Table(name = "client_proforma_lines")
@Getter
@Setter
@NoArgsConstructor
public class ClientProformaLineEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proforma_id", nullable = false)
    private ClientProformaEntity proforma;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "position_id", nullable = false)
    private PurchasePositionEntity position;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id")
    private FinishingMaterialEntity material;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(length = 100)
    private String sku;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit_name", nullable = false, length = 50)
    private String unitName;

    @Column(name = "unit_sale_net", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitSaleNet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vat_rate_id")
    private VatRateEntity vatRate;

    @Column(name = "vat_rate_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal vatRatePercent;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal net;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal vat;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal gross;

    @Column(nullable = false)
    private boolean active = true;
}
