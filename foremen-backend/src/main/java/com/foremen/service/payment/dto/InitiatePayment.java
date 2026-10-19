package com.foremen.service.payment.dto;

import java.math.BigDecimal;

/**
 * FOR-06-07 (Requirements 7.1, 7.6): the input to initiating an online (przelewy24) payment — the
 * reusable server action FOR-09 triggers from the client portal. The service hands it to the
 * {@code OnlinePaymentProvider}, which creates a payment intent and returns a redirect/opaque
 * result; under the {@code No_Op_Provider} (no credentials) it returns a disabled result and marks
 * nothing {@code CONFIRMED} (R7.2).
 *
 * @param scheduleId  the schedule being paid against (required)
 * @param trancheId   the specific tranche the client is paying, or {@code null} to pay the
 *                    unpaid remainder against the nearest open tranche(s)
 * @param amountGross the amount to initiate, strictly positive
 * @param returnUrl   the client-portal URL to return the payer to after the provider ceremony, or
 *                    {@code null} for the provider default
 */
public record InitiatePayment(
        Long scheduleId,
        Long trancheId,
        BigDecimal amountGross,
        String returnUrl) {
}
