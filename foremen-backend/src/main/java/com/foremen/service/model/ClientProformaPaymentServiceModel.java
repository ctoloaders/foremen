package com.foremen.service.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FOR-11-03 (R23.3, R24.5): one ledger payment of a proforma, STAFF detail only. Deliberately carries
 * no counterparty name, account or IBAN data.
 */
public record ClientProformaPaymentServiceModel(Long linkId, LocalDate bookingDate, BigDecimal amount,
                                                String title) {
}
