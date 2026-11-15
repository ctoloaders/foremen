package com.foremen.service.model;

import com.foremen.service.procurement.proforma.ProformaStatus;

/** FOR-11-03 (R24.5): reference from an external order to a proforma of its positions. */
public record ExternalOrderProformaRefServiceModel(Long id, String number, ProformaStatus status) {
}
