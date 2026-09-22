package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A curated grouping of finishing/fixture line items (e.g. "Sanitariat/łazienka", "Podłoga",
 * "Płytki - Gres"), mirroring the {@code Zestawienie} workbook's group rows used by the package
 * zł/m² pricing model (Requirement 6.1). Not project-scoped (global catalog); display name uses
 * the repo-wide {@code firstNonBlank(namePL, nameRU, ...)} PL-fallback convention at the
 * service/mapper layer.
 */
@Entity
@Table(name = "assortment_groups")
@Getter
@Setter
@NoArgsConstructor
public class AssortmentGroupEntity extends BaseEntity {

    @Column(name = "name_ru", nullable = false)
    private String nameRU;

    @Column(name = "name_pl", nullable = false)
    private String namePL;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
