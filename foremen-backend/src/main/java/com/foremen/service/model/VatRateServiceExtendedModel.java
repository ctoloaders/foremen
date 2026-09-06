package com.foremen.service.model;

import java.math.BigDecimal;

public record VatRateServiceExtendedModel(Long id, String code, BigDecimal rate, String nameRU, String namePL,
                                          boolean isDefault, boolean active) {
}
