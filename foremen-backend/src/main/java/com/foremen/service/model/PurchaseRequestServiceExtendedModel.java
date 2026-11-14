package com.foremen.service.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** Write/edit service model for a purchase request: the read model fields (derived ones are output only). */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class PurchaseRequestServiceExtendedModel extends PurchaseRequestServiceModel {
}
