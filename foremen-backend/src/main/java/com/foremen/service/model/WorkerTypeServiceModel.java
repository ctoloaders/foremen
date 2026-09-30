package com.foremen.service.model;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FOR-05-06 — WorkerType list/summary service model. The i18n {@code name} is resolved to the active
 * locale by the generic mapper (mirrors {@code RoomTypeServiceModel} / {@code MeasurementUnitServiceModel}).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerTypeServiceModel {
    private Long id;
    private String code;
    private String name;
    private BigDecimal tierPct;
    private boolean base;
    private Integer orderNo;
    private boolean active;
}
