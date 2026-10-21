package com.foremen.service.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.foremen.dao.model.PaymentChannel;
import com.foremen.dao.model.PayoutStatus;

/**
 * FOR-06-11 task 8.1 — enum-definition unit tests (design §Testing Strategy "Enum definitions").
 *
 * <p>{@link PaymentChannel} is exactly {@code {BANK_TRANSFER, CASH}} (Requirement 2.1) and
 * {@link PayoutStatus} is exactly {@code {PLANNED, PAID}} (Requirement 7) — both frozen so a schema
 * drift or an accidental extra value is caught here rather than at a migration / ABAC seam.
 */
class PaymentChannelEnumTest {

    @Test
    @DisplayName("PaymentChannel is exactly {BANK_TRANSFER, CASH} (R2.1)")
    void paymentChannelHasExactlyTheTwoValues() {
        assertThat(PaymentChannel.values())
                .containsExactly(PaymentChannel.BANK_TRANSFER, PaymentChannel.CASH);
    }

    @Test
    @DisplayName("PayoutStatus is exactly {PLANNED, PAID} (R7)")
    void payoutStatusHasExactlyTheTwoValues() {
        assertThat(PayoutStatus.values())
                .containsExactly(PayoutStatus.PLANNED, PayoutStatus.PAID);
    }
}
