package com.foremen.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MaterialSellerServiceModel {
    private Long id;
    private String code;
    private String name;
    private boolean active;
    private String website;
}
