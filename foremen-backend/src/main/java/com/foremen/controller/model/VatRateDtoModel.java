package com.foremen.controller.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record VatRateDtoModel(Long id, String code, BigDecimal rate, String name,
                              @JsonProperty("isDefault") boolean isDefault, boolean active) {}
