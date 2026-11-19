package com.foremen.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Service-layer read model of a standard work set row (names localized to the request locale). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StandardWorkSetItemServiceModel {
    private Long id;
    private Long roomTypeId;
    private String roomTypeName;
    private Long workItemId;
    private String workItemName;
    private String workItemCode;
    private String workCategoryName;
}
