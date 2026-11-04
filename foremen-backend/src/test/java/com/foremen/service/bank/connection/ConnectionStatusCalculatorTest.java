package com.foremen.service.bank.connection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.foremen.dao.model.BankConnectionStatus;
import com.foremen.service.bank.connector.ConnectionStatusHint;

/**
 * Unit tests for {@link ConnectionStatusCalculator} (FOR-07-04).
 *
 * <p><b>Validates: Requirements 1.2, 1.9</b>
 */
class ConnectionStatusCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final Duration WARN = Duration.ofDays(14);

    @Test
    void activeHintFarFromExpiryIsActive() {
        assertThat(ConnectionStatusCalculator.fromHint(BankConnectionStatus.PENDING_AUTH, ConnectionStatusHint.ACTIVE,
                NOW.plus(Duration.ofDays(60)), NOW, WARN)).isEqualTo(BankConnectionStatus.ACTIVE);
    }

    @Test
    void activeHintWithinWarnWindowIsConsentExpiring() {
        assertThat(ConnectionStatusCalculator.fromHint(BankConnectionStatus.ACTIVE, ConnectionStatusHint.ACTIVE,
                NOW.plus(Duration.ofDays(5)), NOW, WARN)).isEqualTo(BankConnectionStatus.CONSENT_EXPIRING);
    }

    @Test
    void expiredConsentIsReauthRequiredEvenWhenProviderSaysActive() {
        assertThat(ConnectionStatusCalculator.fromHint(BankConnectionStatus.ACTIVE, ConnectionStatusHint.ACTIVE,
                NOW, NOW, WARN)).isEqualTo(BankConnectionStatus.REAUTH_REQUIRED);
    }

    @Test
    void reconnectedConnectionLeavesReauthRequired() {
        assertThat(ConnectionStatusCalculator.fromHint(BankConnectionStatus.REAUTH_REQUIRED,
                ConnectionStatusHint.ACTIVE, NOW.plus(Duration.ofDays(90)), NOW, WARN))
                .isEqualTo(BankConnectionStatus.ACTIVE);
    }

    @Test
    void pendingHintKeepsCurrentStatus() {
        assertThat(ConnectionStatusCalculator.fromHint(BankConnectionStatus.PENDING_AUTH,
                ConnectionStatusHint.PENDING_AUTH, null, NOW, WARN)).isEqualTo(BankConnectionStatus.PENDING_AUTH);
        assertThat(ConnectionStatusCalculator.fromHint(BankConnectionStatus.ACTIVE, null, null, NOW, WARN))
                .isEqualTo(BankConnectionStatus.ACTIVE);
    }

    @Test
    void disabledAndReauthHintsWin() {
        assertThat(ConnectionStatusCalculator.fromHint(BankConnectionStatus.ACTIVE, ConnectionStatusHint.DISABLED,
                NOW.plus(Duration.ofDays(60)), NOW, WARN)).isEqualTo(BankConnectionStatus.DISABLED);
        assertThat(ConnectionStatusCalculator.fromHint(BankConnectionStatus.ACTIVE,
                ConnectionStatusHint.REAUTH_REQUIRED, NOW.plus(Duration.ofDays(60)), NOW, WARN))
                .isEqualTo(BankConnectionStatus.REAUTH_REQUIRED);
    }

    @Test
    void sweepKeepsNonConsentStatusesButExpiryOverridesError() {
        Instant expired = NOW.minusSeconds(1);
        assertThat(ConnectionStatusCalculator.consentStatus(BankConnectionStatus.PENDING_AUTH, expired, NOW, WARN))
                .isEqualTo(BankConnectionStatus.PENDING_AUTH);
        assertThat(ConnectionStatusCalculator.consentStatus(BankConnectionStatus.DISABLED, expired, NOW, WARN))
                .isEqualTo(BankConnectionStatus.DISABLED);
        assertThat(ConnectionStatusCalculator.consentStatus(BankConnectionStatus.ERROR,
                NOW.plus(Duration.ofDays(3)), NOW, WARN)).isEqualTo(BankConnectionStatus.ERROR);
        assertThat(ConnectionStatusCalculator.consentStatus(BankConnectionStatus.ERROR, expired, NOW, WARN))
                .isEqualTo(BankConnectionStatus.REAUTH_REQUIRED);
        assertThat(ConnectionStatusCalculator.consentStatus(BankConnectionStatus.CONSENT_EXPIRING,
                NOW.plus(Duration.ofDays(30)), NOW, WARN)).isEqualTo(BankConnectionStatus.ACTIVE);
        assertThat(ConnectionStatusCalculator.consentStatus(BankConnectionStatus.ACTIVE, null, NOW, WARN))
                .isEqualTo(BankConnectionStatus.ACTIVE);
    }
}
