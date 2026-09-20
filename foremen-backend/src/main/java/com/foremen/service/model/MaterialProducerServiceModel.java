package com.foremen.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-path service model for a material producer. Carries the localized {@code name} plus the
 * resolved {@code imageUrl} (FOR-04-17, Requirement 8.4) built from the stored GCS object key via
 * {@code ImageStorage.toCdnUrl(...)}. Mutable ({@code @Data}) so the service mapper can stamp the
 * resolved image URL after loading.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MaterialProducerServiceModel {
    private Long id;
    private String code;
    private String name;
    private boolean active;
    private String imageUrl;
}
