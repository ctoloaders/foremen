package com.foremen.service.model;

import java.math.BigDecimal;

public record WorkItemServiceExtendedModel(Long id, Long workCategoryId, Long unitId, String nameRU, String namePL,
                                           boolean active, BigDecimal internalPrice) {
}
