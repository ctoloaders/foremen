package com.foremen.service.model;

import java.math.BigDecimal;

import lombok.Data;
import lombok.NoArgsConstructor;

/** FOR-11-03 (R2.2): one proforma line (staff view; carries sale-side values only). */
@Data
@NoArgsConstructor
public class ClientProformaLineServiceModel {
    private Long id;
    private Long positionId;
    private Integer lineNo;
    private Long materialId;
    private String description;
    private String sku;
    private BigDecimal quantity;
    private String unitName;
    private BigDecimal unitSaleNet;
    private Long vatRateId;
    private BigDecimal vatRatePercent;
    private BigDecimal net;
    private BigDecimal vat;
    private BigDecimal gross;
    private boolean active;
}
