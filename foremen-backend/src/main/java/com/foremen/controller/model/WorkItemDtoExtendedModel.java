package com.foremen.controller.model;

import java.math.BigDecimal;

public record WorkItemDtoExtendedModel(Long id, Long workCategoryId, Long unitId, String nameRU, String namePL,
                                       boolean active, BigDecimal internalPrice) {}
