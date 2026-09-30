package com.foremen.service.model;

import java.math.BigDecimal;

/**
 * FOR-05-06 — WorkerType detail service model carrying both locales and the cost-tier fields.
 */
public record WorkerTypeServiceExtendedModel(Long id, String code, String nameRU, String namePL,
                                             BigDecimal tierPct, boolean base, Integer orderNo,
                                             boolean active) {
}
