package com.foremen.controller.model.client;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.foremen.service.procurement.proforma.ProformaStatus;

/**
 * FOR-11-03 (R6.3): the client-safe view of an issued proforma. The only identifier is the proforma
 * id; nothing from the purchase side, the positions or the orders is carried. A reflection leak guard
 * test walks this record tree.
 */
public record ClientProformaClientDto(
        Long id,
        String number,
        ProformaStatus status,
        LocalDate issueDate,
        LocalDate dueDate,
        boolean overdue,
        String currency,
        BigDecimal totalNet,
        BigDecimal totalVat,
        BigDecimal totalGross,
        BigDecimal paidGross,
        BigDecimal remainingGross,
        String paymentTitle,
        List<ClientProformaLineClientDto> lines) {
}
