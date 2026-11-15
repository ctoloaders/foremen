package com.foremen.service.procurement.externalorder;

/** FOR-11-03 (R11.1): who marked an external order paid. The ledger wins over the financier (R16.4). */
public enum PaidSource {
    FINANCIER,
    LEDGER
}
