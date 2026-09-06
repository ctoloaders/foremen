package com.foremen.service.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record WorkPriceServiceExtendedModel(Long id, Long workItemId, Long currencyId, BigDecimal netPrice,
                                            LocalDate validFrom, LocalDate validTo) {
}
