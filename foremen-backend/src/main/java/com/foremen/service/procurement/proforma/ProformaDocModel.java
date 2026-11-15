package com.foremen.service.procurement.proforma;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * FOR-11-03 (R4.5): everything the proforma PDF shows, as plain values. Carries only sale-side data;
 * there is deliberately no purchase price, seller or margin field.
 *
 * @param buyerNip optional tax id of the buyer; {@code null} or blank is omitted from the PDF
 */
public record ProformaDocModel(
        String number,
        LocalDate issueDate,
        LocalDate dueDate,
        String currency,
        String sellerName,
        String sellerAddress,
        String sellerNip,
        String sellerBankAccount,
        String buyerName,
        String buyerAddress,
        String buyerNip,
        List<Line> lines,
        BigDecimal totalNet,
        BigDecimal totalVat,
        BigDecimal totalGross,
        String note) {

    public record Line(
            int lineNo,
            String name,
            String sku,
            BigDecimal quantity,
            String unit,
            BigDecimal unitNet,
            BigDecimal vatPercent,
            BigDecimal net,
            BigDecimal vat,
            BigDecimal gross) {
    }

    /** The transfer title the client must use: {@code Proforma PF/2026/000123}. */
    public String paymentTitle(String titleLabel) {
        return titleLabel + " " + number;
    }
}
