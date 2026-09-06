package com.foremen.controller.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record VatRateUpdateResponse(Long id, String code, BigDecimal rate, String nameRU, String namePL,
                                    @JsonProperty("isDefault") boolean isDefault, boolean active) {}
