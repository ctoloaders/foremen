package com.foremen.service.bank.detect;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * FOR-07-04 (Requirement 14, design "RuleMatcher") — the facts of one ledger line that the pure
 * {@link RuleMatcher} scores candidates against.
 *
 * @param direction         {@link Direction#INCOME} or {@link Direction#EXPENSE}
 * @param amount            the line amount; stored as its absolute value
 * @param bookingDate       the booking date
 * @param title             the transfer title (may be {@code null})
 * @param counterpartyName  the counterparty name (may be {@code null})
 * @param counterpartyIban  the counterparty IBAN, raw or normalized (may be {@code null})
 * @param counterpartyKey   the counterparty key ({@code IBAN:…} / {@code NAME:…}, may be {@code null})
 */
public record TxnFacts(
        Direction direction,
        BigDecimal amount,
        LocalDate bookingDate,
        String title,
        String counterpartyName,
        String counterpartyIban,
        String counterpartyKey) {

    /** Line direction (mirrors {@code bank_transactions.direction}). */
    public enum Direction {
        INCOME,
        EXPENSE
    }

    public TxnFacts {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(bookingDate, "bookingDate");
        amount = amount.abs();
    }
}
