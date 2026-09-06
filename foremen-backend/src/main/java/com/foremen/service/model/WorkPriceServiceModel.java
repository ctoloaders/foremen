package com.foremen.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkPriceServiceModel {
    private Long id;
    private Long workItemId;
    private String workItemName;
    private Long currencyId;
    private String currencyCode;
    private BigDecimal netPrice;
    private LocalDate validFrom;
    private LocalDate validTo;
    private boolean current;
}
