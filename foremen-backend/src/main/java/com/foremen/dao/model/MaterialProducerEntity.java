package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "material_producers")
@Getter
@Setter
@NoArgsConstructor
public class MaterialProducerEntity extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "name_ru", nullable = false)
    private String nameRU;

    @Column(name = "name_pl", nullable = false)
    private String namePL;

    @Column(nullable = false)
    private boolean active = true;

    /**
     * Optional ImageStorage GCS object key for the producer logo/photo (FOR-04-17, Requirement 8.1).
     * Nullable and additive: the column is created by changeset {@code 059} (task 8.2). The entity
     * persists only the bucket-relative object key (never the CDN URL); the resolved CDN URL is
     * built at read time via {@code ImageStorage.toCdnUrl(...)} and exposed on the DTO.
     */
    @Column(name = "image", length = 512)
    private String image;
}
