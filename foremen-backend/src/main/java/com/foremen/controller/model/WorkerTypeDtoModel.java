package com.foremen.controller.model;

import java.math.BigDecimal;

public record WorkerTypeDtoModel(Long id, String code, String name, BigDecimal tierPct,
                                 boolean base, Integer orderNo, boolean active) {}
