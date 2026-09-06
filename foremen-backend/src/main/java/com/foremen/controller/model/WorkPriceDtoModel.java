package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record WorkPriceDtoModel(Long id, Long workItemId, String workItemName, Long currencyId, String currencyCode,
                                BigDecimal netPrice, LocalDate validFrom, LocalDate validTo, boolean current) {}
