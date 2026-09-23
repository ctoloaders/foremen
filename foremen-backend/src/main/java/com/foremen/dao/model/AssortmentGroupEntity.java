package com.foremen.dao.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
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

    /**
     * The group's single reference quantity (ILOSC): applied to the sum of the group's positions'
     * avg prices when computing the group's zł/m² contribution — {@code (Σ avgPrice × referenceQty)
     * / 50} (FOR-05-04-UI). Defaults to 1 at the DB level.
     */
    @Column(name = "reference_qty", nullable = false)
    private BigDecimal referenceQty;

    /** The group's reference-quantity unit — a plain string, {@code 'szt'} or {@code 'm2'}. */
    @Column(name = "reference_unit", nullable = false)
    private String referenceUnit;
}
