package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * A computed money band ({@code min}..{@code max}) in PLN per one work-unit, exposed on the
 * work-material-consumption DTOs (FOR-04-19). Mirrors the shape of the internal
 * {@code MaterialRangeResolver.MoneyRange} but lives in the controller-model layer so the read DTOs
 * stay decoupled from the pricing internals.
 *
 * <p>Used for a consumption row's {@code typeBatchRange} — the type-level analog batch band
 * {@code normQty × [MIN..MAX retailNet]} over all active priced materials of the row's type in the
 * row's package. An empty batch (no priced material of the type in the package) collapses to an
 * explicit {@code 0..0} rather than a null band, so a missing catalog item stays visible.
 *
 * @param min the low end of the band (never {@code null}; {@code 0} for an empty batch)
 * @param max the high end of the band (never {@code null}; {@code 0} for an empty batch)
 */
public record MoneyRangeDto(BigDecimal min, BigDecimal max) {}
