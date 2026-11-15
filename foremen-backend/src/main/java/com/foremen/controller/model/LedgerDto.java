package com.foremen.controller.model;

import java.math.BigDecimal;

/** FOR-11-05 (R3.1, R9.5): quantity ledger of one position. Quantities only; no money. */
public record LedgerDto(BigDecimal cap, BigDecimal acceptedNet, BigDecimal missing, BigDecimal returned,
                        BigDecimal outstanding, BigDecimal surplus, BigDecimal inFlight,
                        BigDecimal committed, BigDecimal remainder) {
}
