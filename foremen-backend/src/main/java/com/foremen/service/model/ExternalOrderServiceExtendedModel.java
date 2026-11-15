package com.foremen.service.model;

import java.math.BigDecimal;
import java.util.List;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * FOR-11-03 detail / write model of an external order. On create, {@code lines} (optional) are the
 * initial lines; on read it carries lines, documents and the ledger-derived paid state. {@code lines}
 * and {@code documents} are ignored by the header update (they have their own replace operations).
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ExternalOrderServiceExtendedModel extends ExternalOrderServiceModel {

    private List<ExternalOrderLineServiceModel> lines;
    private List<ExternalOrderDocumentServiceModel> documents;

    // --- derived, output only ---
    /** R24.5: proformas of the order's positions through ACTIVE proforma lines. */
    private List<ExternalOrderProformaRefServiceModel> proformas;
    /** Σ {@code EXTERNAL_ORDER_PAYMENT} link amounts (R11.5). */
    private BigDecimal ledgerPaidGross;
    /** R16.2: at least one document and every document paid. */
    private boolean documentsPaid;
}
