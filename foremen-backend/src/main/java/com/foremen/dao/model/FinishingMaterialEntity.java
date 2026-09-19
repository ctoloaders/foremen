package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "finishing_materials")
@Getter
@Setter
@NoArgsConstructor
public class FinishingMaterialEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private MaterialCategoryEntity category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false)
    private MaterialEntity material;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "type_id")
    private MaterialTypeEntity type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "producer_id")
    private MaterialProducerEntity producer;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "finishing_material_packages",
            joinColumns = @JoinColumn(name = "finishing_material_id"),
            inverseJoinColumns = @JoinColumn(name = "offer_package_id"))
    private Set<OfferPackageEntity> packages = new HashSet<>();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unit_id", nullable = false)
    private MeasurementUnitEntity unit;

    @Column(name = "model", length = 255)
    private String model;

    @Column(name = "sku", length = 255)
    private String sku;

    @Column(name = "features", columnDefinition = "text")
    private String features;

    @Column(name = "purchase_price", precision = 12, scale = 2)
    private BigDecimal purchasePrice;

    @Column(name = "retail_gross", precision = 12, scale = 2)
    private BigDecimal retailGross;

    @Column(name = "retail_net", precision = 12, scale = 2)
    private BigDecimal retailNet;

    @Column(name = "link", length = 1024)
    private String link;

    /** GCS object key (NOT the CDN URL); resolved to a CDN URL only on the DTO. */
    @Column(name = "photo", length = 512)
    private String photo;

    @Column(nullable = false)
    private boolean active = true;
}
