package com.foremen.service.integration.entity;

import com.foremen.dao.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "sample_entity")
@Getter
@Setter
@NoArgsConstructor
public class SampleEntity extends BaseEntity {

    @Column(name = "name_ru")
    private String nameRU;

    @Column(name = "name_pl")
    private String namePL;

    @Column(name = "status")
    private String status;

    @Column(name = "code")
    private String code;

    @Column(name = "city")
    private String city;

    @Column(name = "country")
    private String country;

    @Column(name = "age")
    private Integer age;

    @Column(name = "price", precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "deleted")
    private Boolean deleted;
}
