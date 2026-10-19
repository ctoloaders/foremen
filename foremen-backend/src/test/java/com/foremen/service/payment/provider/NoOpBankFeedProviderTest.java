package com.foremen.service.payment.provider;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * FOR-06-07 (Requirement 8.2): unit tests for {@link NoOpBankFeedProvider}, the default disabled
 * bank-feed provider active when no mass-collect credentials are configured.
 *
 * <p>Plain JUnit 5 unit tests (no Spring context): the No-Op provider has no collaborators, so it is
 * exercised directly.
 */
class NoOpBankFeedProviderTest {

    private final NoOpBankFeedProvider provider = new NoOpBankFeedProvider();

    @Test
    @DisplayName("poll returns an empty, non-null list and does not throw (R8.2)")
    void pollReturnsEmptyList() {
        List<IncomingBankTxn> result = provider.poll();

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("poll does not throw (R8.2)")
    void pollDoesNotThrow() {
        assertThatCode(provider::poll).doesNotThrowAnyException();
    }
}
