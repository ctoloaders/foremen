package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "construction_materials")
@Getter
@Setter
@NoArgsConstructor
public class ConstructionMaterialEntity extends BaseEntity {

    @Column(name = "name_ru", nullable = false)
    private String nameRU;

    @Column(name = "name_pl", nullable = false)
    private String namePL;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "type_id", nullable = false)
    private ConstructionMaterialTypeEntity type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "producer_id")
    private MaterialProducerEntity producer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_id")
    private MaterialSellerEntity seller;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "construction_material_packages",
            joinColumns = @JoinColumn(name = "construction_material_id"),
            inverseJoinColumns = @JoinColumn(name = "offer_package_id"))
    private Set<OfferPackageEntity> packages = new HashSet<>();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unit_id", nullable = false)
    private MeasurementUnitEntity unit;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "currency_id", nullable = false)
    private CurrencyEntity currency;

    @Column(name = "purchase_price", precision = 12, scale = 2)
    private BigDecimal purchasePrice;

    @Column(name = "retail_gross", precision = 12, scale = 2)
    private BigDecimal retailGross;

    @Column(name = "retail_net", precision = 12, scale = 2)
    private BigDecimal retailNet;

    @Column(name = "website", length = 255)
    private String website;

    /** GCS object key (NOT the CDN URL); resolved to a CDN URL only on the DTO. */
    @Column(name = "image", length = 512)
    private String image;

    @Column(nullable = false)
    private boolean active = true;
}
