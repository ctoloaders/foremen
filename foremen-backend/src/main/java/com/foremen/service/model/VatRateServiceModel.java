package com.foremen.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VatRateServiceModel {
    private Long id;
    private String code;
    private BigDecimal rate;
    private String name;
    private boolean isDefault;
    private boolean active;
}
