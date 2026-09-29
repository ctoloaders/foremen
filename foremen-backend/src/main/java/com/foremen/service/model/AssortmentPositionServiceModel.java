package com.foremen.service.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Service-layer read model for an {@code AssortmentPosition} row (FOR-05-04-UI assortment rework):
 * a GLOBAL, per-group, material-type-backed position. {@code assortmentGroupName} and
 * {@code materialTypeName} are localized display names (RU when the request locale language is
 * {@code ru}, PL otherwise), resolved in the service mapper.
 *
 * <p>The per-package work-item link is NOT carried on this generic CRUD model (FOR-05-05 Wave 1b,
 * #8): a position now links to a possibly different work per package, which the generic CRUD cannot
 * express — those links are read via the package editor and written via the dedicated per-package
 * links endpoint.
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
