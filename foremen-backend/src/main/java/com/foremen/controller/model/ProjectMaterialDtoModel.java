package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.List;

/** List/read DTO for a Project_Material: the finishing DTO fields plus {@code projectId}. */
public record ProjectMaterialDtoModel(
        Long id,
        Long projectId,
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
        BigDecimal costNet,
        String link,
        String photoUrl,
        boolean active,
        Long sellerId,
        String sellerName
) {}
