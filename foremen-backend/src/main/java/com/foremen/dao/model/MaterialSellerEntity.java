package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "material_sellers")
@Getter
@Setter
@NoArgsConstructor
public class MaterialSellerEntity extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "name_ru", nullable = false)
    private String nameRU;

    @Column(name = "name_pl", nullable = false)
    private String namePL;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "website", length = 255)
    private String website;
}
