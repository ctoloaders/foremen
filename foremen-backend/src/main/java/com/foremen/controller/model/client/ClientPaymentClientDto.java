package com.foremen.controller.model.client;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.foremen.controller.model.ClientPaymentLineSummaryDto;
import com.foremen.controller.model.ClientPaymentView;
import com.foremen.service.clientpayment.ClientPaymentKind;
import com.foremen.service.clientpayment.DisplayStatus;

/**
 * FOR-11-06 (R1.7, R3.2): the client-safe view of a payment. An explicit allow-list: no note, no
 * stored status, no project / schedule / detail ids, no cancel reason. The only bank datum is
 * {@code payeeBankAccount}, the account the client pays to (the issue snapshot for an issued
 * materials payment, else the effective project account).
 */
public record ClientPaymentClientDto(
        Long id,
        ClientPaymentKind kind,
        DisplayStatus status,
        boolean overdue,
        String number,
        LocalDate issueDate,
        LocalDate dueDate,
        String currency,
        BigDecimal amountGross,
        BigDecimal paidGross,
        BigDecimal remainingGross,
        String payeeBankAccount,
        String paymentTitle,
        List<ClientPaymentLineSummaryDto> lines) implements ClientPaymentView {
}
