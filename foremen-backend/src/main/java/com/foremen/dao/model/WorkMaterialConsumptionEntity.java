package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * A single work-catalog material-consumption norm (FOR-04-19): one
 * {@code (work item, offer package, material TYPE)} row binding a {@link WorkItemEntity} to how much
 * of a material analog GROUP it consumes per one work-unit, in a given offer package.
 *
 * <p>The row references a material TYPE (the analog group), never a concrete material — exactly one
 * of {@link #constructionMaterialType} / {@link #finishingMaterialType} is non-null, matching
 * {@link #branch}. There is NO localized {@code name}/{@code code}; the only i18n text owned by the
 * entity is the {@code justificationRU}/{@code justificationPL} pair. The computed money range
 * (вилка) is never stored — it is derived at read time from the analog batch.
 */
@Entity
@Table(name = "work_material_consumptions")
@Getter
@Setter
@NoArgsConstructor
public class WorkMaterialConsumptionEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_item_id", nullable = false)
    private WorkItemEntity workItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offer_package_id", nullable = false)
    private OfferPackageEntity offerPackage;

    /** The numerator unit of the norm (kg/l/m²/m³/szt), distinct from the work item's own unit. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_unit_id", nullable = false)
    private MeasurementUnitEntity materialUnit;

    @Enumerated(EnumType.STRING)
    @Column(name = "branch", nullable = false, length = 32)
    private ConsumptionBranch branch;

    /** Analog GROUP for the construction branch; non-null iff {@code branch == construction}. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "construction_material_type_id")
    private ConstructionMaterialTypeEntity constructionMaterialType;

    /** Analog GROUP for the finishing branch; non-null iff {@code branch == finishing}. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "finishing_material_type_id")
    private MaterialTypeEntity finishingMaterialType;

    /** Material-unit per one work-unit; non-negative. */
    @Column(name = "norm_qty", nullable = false, precision = 12, scale = 4)
    private BigDecimal normQty;

    @Column(name = "waste_pct", precision = 5, scale = 2)
    private BigDecimal wastePct;

    @Column(name = "justification_ru", columnDefinition = "text")
    private String justificationRU;

    @Column(name = "justification_pl", columnDefinition = "text")
    private String justificationPL;

    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    @Column(name = "source_doc", nullable = false)
    private String sourceDoc;

    @Column(name = "source_url", length = 1024)
    private String sourceUrl;

    @Column(name = "source_ref", nullable = false)
    private String sourceRef;
}
