package com.foremen.controller.model;

import java.math.BigDecimal;

public record WorkerTypeDtoExtendedModel(Long id, String code, String nameRU, String namePL,
                                         BigDecimal tierPct, boolean base, Integer orderNo,
                                         boolean active) {}
