package com.foremen.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkItemServiceModel {
    private Long id;
    private Long workCategoryId;
    private String workCategoryName;
    private Long unitId;
    private String unitName;
    private String name;
    private boolean active;
}
