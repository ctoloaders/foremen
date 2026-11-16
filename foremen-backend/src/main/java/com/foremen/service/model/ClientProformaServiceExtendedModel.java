package com.foremen.service.model;

import java.util.List;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * FOR-11-03 detail / write model of a client proforma. On write {@code positionIds} is the requested
 * set of positions (R3.1, R3.5); on read {@code lines} carries the lines.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ClientProformaServiceExtendedModel extends ClientProformaServiceModel {
    private List<Long> positionIds;
    private List<ClientProformaLineServiceModel> lines;
    /** Read-only ledger payments (staff detail only, no counterparty data); ignored on write. */
    private List<ClientProformaPaymentServiceModel> payments;

    /** FOR-11-06 (R10.5): a line holds a received buyer-added position that is still unpaid; read only. */
    private boolean containsDeliveredItems;
}
