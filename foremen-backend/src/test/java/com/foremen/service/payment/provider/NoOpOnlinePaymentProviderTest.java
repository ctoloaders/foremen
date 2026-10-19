package com.foremen.service.payment.provider;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.foremen.service.payment.dto.InitiatePayment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * FOR-06-07 (Requirements 7.2, 7.5): unit tests for {@link NoOpOnlinePaymentProvider}, the default
 * disabled online-payment provider active when no przelewy24 credentials are configured.
 *
 * <p>Plain JUnit 5 unit tests (no Spring context): the No-Op provider has no collaborators, so it is
 * exercised directly.
 */
class NoOpOnlinePaymentProviderTest {

    private final NoOpOnlinePaymentProvider provider = new NoOpOnlinePaymentProvider();

    @Test
    @DisplayName("initiate returns a disabled result (not enabled, no redirect/externalRef) and does"
            + " not throw (R7.2)")
    void initiateReturnsDisabledResult() {
        InitiatePayment req =
                new InitiatePayment(1L, 2L, new BigDecimal("100.00"), "https://return.example");

        OnlineInitiateResult result = provider.initiate(req);

        assertThat(result).isNotNull();
        assertThat(result.enabled()).isFalse();
        assertThat(result.redirectUrl()).isNull();
        assertThat(result.externalRef()).isNull();
    }

    @Test
    @DisplayName("initiate does not throw even for a null request (R7.2)")
    void initiateDoesNotThrow() {
        assertThatCode(() -> provider.initiate(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("verifySignature returns false: no live webhook is accepted (R7.5)")
    void verifySignatureReturnsFalse() {
        RawWebhook raw = new RawWebhook(new byte[] {1, 2, 3}, Map.of("X-Signature", "whatever"));

        assertThat(provider.verifySignature(raw)).isFalse();
    }
}
