package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record WorkPriceCreateResponse(Long id, Long workItemId, Long currencyId, BigDecimal netPrice,
                                      LocalDate validFrom, LocalDate validTo) {}
