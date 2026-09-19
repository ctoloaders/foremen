package com.foremen.service.model;

import com.foremen.controller.model.RefDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-path service model for a finishing material.
 *
 * <p>Carries the resolved, localized references as {@link RefDto} objects ({@code category}/
 * {@code material}/{@code type}/{@code producer}/{@code unit}, {@code packages} as a list), the
 * derived {@code label} (built at read time from {@code material} name + {@code model}), the
 * free-text {@code model}/{@code sku}/{@code features}, the three prices, the {@code link}, the
 * resolved {@code photoUrl} (built from the stored GCS object key via {@code ImageStorage.toCdnUrl}),
 * and {@code active}. Unlike {@code ConstructionMaterialServiceModel} there is no localized
 * {@code name}, no {@code seller}/{@code currency}, and no computed price ranges. Mutable
 * ({@code @Data}) so the service can stamp the resolved photo URL and derived label after loading.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FinishingMaterialServiceModel {
    private Long id;
    private String label;
    private RefDto category;
    private RefDto material;
    private RefDto type;
    private RefDto producer;
    private List<RefDto> packages = new ArrayList<>();
    private RefDto unit;
    private String model;
    private String sku;
    private String features;
    private BigDecimal purchasePrice;
    private BigDecimal retailGross;
    private BigDecimal retailNet;
    private String link;
    private String photoUrl;
    private boolean active;
}
