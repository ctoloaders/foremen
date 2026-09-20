package com.foremen.controller.model;

import java.math.BigDecimal;

/**
 * One concrete analog material of a consumption row's material TYPE that belongs to the row's offer
 * package (FOR-04-19, Requirement 5.4). Rendered in the drill-in detail as an entry of the analog
 * batch behind a {@code WorkMaterialConsumption} norm.
 *
 * <p>{@code producer} and {@code seller} are the material's producer/seller display names (localized
 * where applicable, plain strings here since the drill-in only needs to show them). {@code unit} is
 * the material's own unit as a localized {@link RefDto}. {@code retailNet} is the material's net
 * retail price used to form the type-level band (null when the material is unpriced — such a
 * material is excluded from the band's MIN/MAX). {@code moneyCost} is the per-material money cost per
 * one work-unit, computed at read time as {@code normQty × retailNet} (null when {@code retailNet}
 * is null).
 *
 * @param id        the concrete material id
 * @param name      the concrete material's display name
 * @param producer  the material's producer display name (may be null)
 * @param seller    the material's seller display name (may be null)
 * @param unit      the material's own unit as a localized reference
 * @param retailNet the material's net retail price (null when unpriced)
 * @param moneyCost the per-material money cost per work-unit ({@code normQty × retailNet}; null when
 *                  {@code retailNet} is null)
 */
public record AnalogMaterialDto(
        Long id,
        String name,
        String producer,
        String seller,
        RefDto unit,
        BigDecimal retailNet,
        BigDecimal moneyCost
) {}
