package com.foremen.service.integration.entity;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class SampleServiceModel {

    private Long id;
    private String name;
    private String status;
    private String code;
    private String city;
    private String country;
    private Integer age;
    private BigDecimal price;
    private Boolean deleted;
}
