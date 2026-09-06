package com.foremen.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkCategoryServiceModel {
    private Long id;
    private String code;
    private Integer orderNo;
    private String name;
    private boolean active;
}
