package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * List/read DTO for a finishing material (one catalog row).
 *
 * <p>References are exposed as localized {@link RefDto} objects ({@code category}/{@code material}/
 * {@code type}/{@code producer}/{@code unit}), with {@code packages} as a {@code List<RefDto>}.
 * {@code label} is the derived human-readable list label ({@code material} name + {@code model}),
 * never persisted. {@code photoUrl} is the resolved CDN URL (null when the entity has no photo); the
 * persisted GCS object key is never returned on the read/list DTO. Unlike the construction-material
 * DTO there is no localized {@code name} and no computed price ranges.
 */
public record FinishingMaterialDtoModel(
        Long id,
        String label,
        RefDto category,
        RefDto material,
        RefDto type,
        RefDto producer,
        List<RefDto> packages,
        RefDto unit,
        String model,
        String sku,
        String features,
        BigDecimal purchasePrice,
        BigDecimal retailGross,
        BigDecimal retailNet,
        String link,
        String photoUrl,
        boolean active
) {}
