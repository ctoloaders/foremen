package com.foremen.controller.model;

/** FOR-11-01 (task 8.7): one active BUYER user for the request dialog, {@code GET /api/procurement/buyers}. */
public record ProcurementBuyerDto(Long id, String name) {
}
