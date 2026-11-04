package com.foremen.service.bank.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.foremen.dao.BankConnectionDao;
import com.foremen.dao.model.BankConnectionEntity;

/**
 * Unit tests for {@link BankNotificationThrottle} (FOR-07-04 task 6.10): the syncFailed 24 h window
 * and the consentExpiring once-per-consent marker.
 *
 * <p><b>Validates: Requirements 22.2</b>
 */
@ExtendWith(MockitoExtension.class)
class BankNotificationThrottleTest {

    private static final Long ID = 5L;
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @Mock
    private BankConnectionDao connectionDao;

    private BankNotificationThrottle throttle;
    private BankConnectionEntity connection;

    @BeforeEach
    void setUp() {
        throttle = new BankNotificationThrottle(connectionDao);
        throttle.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        connection = new BankConnectionEntity();
        connection.setId(ID);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    }

    @Test
    @DisplayName("syncFailed: first failure is claimed and stamped")
    void syncFailedFirstClaim() {
        when(connectionDao.findById(ID)).thenReturn(Optional.of(connection));

        assertThat(throttle.claimSyncFailed(ID)).isTrue();
        assertThat(connection.getLastSyncFailedNotifiedAt()).isEqualTo(now());
        verify(connectionDao).save(connection);
    }

    @Test
    @DisplayName("syncFailed: a failure within 24 h is throttled")
    void syncFailedWithinWindow() {
        connection.setLastSyncFailedNotifiedAt(now().minusHours(23));
        when(connectionDao.findById(ID)).thenReturn(Optional.of(connection));

        assertThat(throttle.claimSyncFailed(ID)).isFalse();
        verify(connectionDao, never()).save(any());
    }

    @Test
    @DisplayName("syncFailed: after 24 h it is claimed again")
    void syncFailedAfterWindow() {
        connection.setLastSyncFailedNotifiedAt(now().minusHours(24));
        when(connectionDao.findById(ID)).thenReturn(Optional.of(connection));

        assertThat(throttle.claimSyncFailed(ID)).isTrue();
        assertThat(connection.getLastSyncFailedNotifiedAt()).isEqualTo(now());
    }

    @Test
    @DisplayName("consentExpiring: once per consent, a new consent is notified again")
    void consentExpiringOncePerConsent() {
        when(connectionDao.findById(ID)).thenReturn(Optional.of(connection));

        assertThat(throttle.claimConsentExpiring(ID, "c-1")).isTrue();
        assertThat(connection.getConsentExpiringNotifiedFor()).isEqualTo("c-1");
        assertThat(throttle.claimConsentExpiring(ID, "c-1")).isFalse();
        assertThat(throttle.claimConsentExpiring(ID, "c-2")).isTrue();
        assertThat(connection.getConsentExpiringNotifiedFor()).isEqualTo("c-2");
    }

    @Test
    @DisplayName("consentExpiring: without a consent id the key falls back to the stored consent id, then expiry")
    void consentKeyFallbacks() {
        connection.setExternalConsentId("stored");
        assertThat(BankNotificationThrottle.consentKey(connection, null)).isEqualTo("stored");

        connection.setExternalConsentId(null);
        connection.setConsentExpiresAt(now());
        assertThat(BankNotificationThrottle.consentKey(connection, " ")).isEqualTo("exp:" + NOW);
    }

    @Test
    @DisplayName("a missing connection is never claimed")
    void missingConnection() {
        when(connectionDao.findById(ID)).thenReturn(Optional.empty());

        assertThat(throttle.claimSyncFailed(ID)).isFalse();
        assertThat(throttle.claimConsentExpiring(ID, "c")).isFalse();
        assertThat(throttle.claimSyncFailed(null)).isFalse();
    }
}
