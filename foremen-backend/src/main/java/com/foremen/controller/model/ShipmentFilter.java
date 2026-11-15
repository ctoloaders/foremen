package com.foremen.controller.model;

import java.time.LocalDate;

import com.foremen.dao.model.ShipmentStatus;

/**
 * FOR-11-04 (R16.1): list filters; every field is optional. {@code delayed=true} keeps active shipments
 * (not DELIVERED / CANCELLED) whose ETA is before today (Europe/Warsaw); {@code false} keeps the others.
 */
public record ShipmentFilter(
        Long projectId,
        ShipmentStatus status,
        String carrierCode,
        Long externalOrderId,
        Long positionId,
        LocalDate etaFrom,
        LocalDate etaTo,
        Boolean degraded,
        Boolean delayed,
        String text) {
}
