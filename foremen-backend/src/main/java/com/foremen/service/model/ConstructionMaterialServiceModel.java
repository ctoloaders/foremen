package com.foremen.service.model;

import com.foremen.controller.model.PriceRangeEntry;
import com.foremen.controller.model.RefDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-path service model for a construction material.
 *
 * <p>Carries the resolved, localized references as {@link RefDto} objects ({@code type}/
 * {@code producer}/{@code seller}/{@code unit}/{@code currency}, {@code packages} as a list), the
 * localized {@code name}, the three prices, {@code website}, the resolved {@code imageUrl} (built
 * from the stored GCS object key via {@code ImageStorage.toCdnUrl}), {@code active}, and the computed
 * per-({@code package}, {@code type}) {@code priceRanges}. Mutable ({@code @Data}) so the service can
 * stamp the resolved image URL and populate the price ranges after loading.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConstructionMaterialServiceModel {
    private Long id;
    private String name;
    private RefDto type;
    private RefDto producer;
    private RefDto seller;
    private List<RefDto> packages = new ArrayList<>();
    private RefDto unit;
    private RefDto currency;
    private BigDecimal purchasePrice;
    private BigDecimal retailGross;
    private BigDecimal retailNet;
    private String website;
    private String imageUrl;
    private boolean active;
    private List<PriceRangeEntry> priceRanges = new ArrayList<>();
}
