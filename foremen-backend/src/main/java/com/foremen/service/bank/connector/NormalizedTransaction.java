package com.foremen.service.bank.connector;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * FOR-07-04 (Requirement 4.2): a provider transaction normalized by the connector. Persistence,
 * deduplication and pending → posted handling belong to the ingest service.
 *
 * @param externalId          provider transaction id
 * @param status              {@link TxnStatus#POSTED} or {@link TxnStatus#PENDING}
 * @param amount              signed amount (negative = expense)
 * @param currency            ISO 4217 code
 * @param operationDate       operation (made-on) date
 * @param bookingDate         booking date; nullable for pending
 * @param title               description / title
 * @param counterpartyName    payer / payee name; nullable
 * @param counterpartyIbanRaw payer / payee account as reported; nullable
 * @param providerCategory    provider category; nullable
 * @param counterpartyRaw     raw counterparty attributes as reported; nullable
 * @param payload             full raw provider payload; nullable
 * @param providerDuplicate   provider flagged the row as a duplicate
 */
public record NormalizedTransaction(
        String externalId,
        TxnStatus status,
        BigDecimal amount,
        String currency,
        LocalDate operationDate,
        LocalDate bookingDate,
        String title,
        String counterpartyName,
        String counterpartyIbanRaw,
        String providerCategory,
        JsonNode counterpartyRaw,
        JsonNode payload,
        boolean providerDuplicate) {
}
