package com.foremen.controller.model;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.foremen.dao.model.PositionStatus;

/**
 * One planned delivery for the foreman (FOR-11-00 R23.4). Carries no Money_Field by construction.
 * {@code materialModel} / {@code producer} describe the actual material when set, else the planned one.
 */
public record UpcomingDeliveryDto(
        Long positionId,
        Long projectId,
        String projectName,
        String materialModel,
        String producer,
        BigDecimal quantity,
        String unitName,
        LocalDate plannedDeliveryDate,
        PositionStatus status,
        boolean overdue,
        Long requestId) {
}
