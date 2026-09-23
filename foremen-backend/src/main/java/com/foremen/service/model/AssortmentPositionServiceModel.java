package com.foremen.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Service-layer read model for an {@code AssortmentPosition} row (FOR-05-04-UI assortment rework):
 * a GLOBAL, per-group, material-type-backed position. {@code assortmentGroupName} and
 * {@code materialTypeName} are localized display names (RU when the request locale language is
 * {@code ru}, PL otherwise), resolved in the service mapper.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssortmentPositionServiceModel {
    private Long id;
    private Long assortmentGroupId;
    private String assortmentGroupName;
    private Long materialTypeId;
    private String materialTypeName;
    private Integer sortOrder;
}
