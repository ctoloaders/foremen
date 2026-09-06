package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "offer_packages")
@Getter
@Setter
@NoArgsConstructor
public class OfferPackageEntity extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "order_no", nullable = false)
    private Integer orderNo;

    @Column(name = "name_ru", nullable = false)
    private String nameRU;

    @Column(name = "name_pl", nullable = false)
    private String namePL;

    @Column(nullable = false)
    private boolean active = true;
}
