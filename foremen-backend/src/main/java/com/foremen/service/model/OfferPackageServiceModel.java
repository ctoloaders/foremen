package com.foremen.service.model;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OfferPackageServiceModel {
    private Long id;
    private String code;
    private Integer orderNo;
    private String name;
    private boolean active;
    /** Read-only denormalized package zł/m² cache (FOR-05-04-UI); nullable. */
    private BigDecimal zlM2;
}
