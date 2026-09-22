package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * Response wrapper for the computed package zł/m² price (FOR-05-04, Requirement 6.3, 6.4, 6.5).
 * {@code value} is the raw zł/m² figure, rounded to 2 decimals, recomputed from the current
 * assortment data on every call — never cached. It is NOT multiplied by a project's floor area;
 * that multiplication is the downstream FOR-05-06 consumer's responsibility (Requirement 6.8).
 *
 * @param value the package's zł/m² price
 */
public record PackageZlM2Response(BigDecimal value) {}
