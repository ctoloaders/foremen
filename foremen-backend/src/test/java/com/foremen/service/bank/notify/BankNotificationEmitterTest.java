package com.foremen.service.bank.notify;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.foremen.dao.UserDao;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.offer.NotificationService;

/**
 * Plain Mockito unit tests for {@link BankNotificationEmitter} (FOR-07-04 task 6.10): the five
 * type keys and deep-links, company-role recipients (active, de-duplicated), throttle gating, and
 * best-effort failure handling.
 *
 * <p><b>Validates: Requirements 22.1, 22.2, 22.6</b>
 */
@ExtendWith(MockitoExtension.class)
class BankNotificationEmitterTest {

    private static final Long CONNECTION_ID = 5L;

    private static final Long ADMIN_UID = 1L;
    private static final Long FINANCIER_UID = 2L;
    private static final Long MANAGER_UID = 3L;
    private static final Long INACTIVE_FINANCIER_UID = 4L;

    @Mock
    private NotificationService notificationService;
    @Mock
    private UserDao userDao;
    @Mock
    private BankNotificationThrottle throttle;

    private BankNotificationEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = new BankNotificationEmitter(notificationService, userDao, throttle);
        lenient().when(userDao.findByRoleCode("ADMIN")).thenReturn(List.of(user(ADMIN_UID, true)));
        lenient().when(userDao.findByRoleCode("FINANCIER")).thenReturn(List.of(
                user(FINANCIER_UID, true), user(INACTIVE_FINANCIER_UID, false)));
        lenient().when(userDao.findByRoleCode("MANAGER")).thenReturn(List.of(user(MANAGER_UID, true)));
    }

    private static UserEntity user(Long id, boolean active) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setActive(active);
        return u;
    }

    private void verifyAdminAndFinancierOnly(String type, String deepLink) {
        verify(notificationService).create(eq(ADMIN_UID), eq(type), isNull(), eq(deepLink));
        verify(notificationService).create(eq(FINANCIER_UID), eq(type), isNull(), eq(deepLink));
        verify(notificationService, never()).create(eq(MANAGER_UID), anyString(), isNull(), anyString());
        verify(notificationService, never()).create(eq(INACTIVE_FINANCIER_UID), anyString(), isNull(), anyString());
        verify(notificationService, times(2)).create(anyLong(), anyString(), isNull(), anyString());
    }

    @Test
    @DisplayName("consentExpiring (claimed) notifies active ADMIN + FINANCIER with the connections deep-link (R22.2 row 1)")
    void consentExpiringNotifiesAdminAndFinancier() {
        when(throttle.claimConsentExpiring(CONNECTION_ID, "c-1")).thenReturn(true);

        emitter.onConsentExpiring(new ConsentExpiringEvent(CONNECTION_ID, "c-1"));

        verifyAdminAndFinancierOnly("notification.bank.consentExpiring", "/bank/connections?connectionId=5");
    }

    @Test
    @DisplayName("consentExpiring already notified for the consent sends nothing (once per consent)")
    void consentExpiringAlreadyNotifiedIsSkipped() {
        when(throttle.claimConsentExpiring(CONNECTION_ID, "c-1")).thenReturn(false);

        emitter.onConsentExpiring(new ConsentExpiringEvent(CONNECTION_ID, "c-1"));

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("reauthRequired notifies ADMIN + FINANCIER with the connections deep-link (R22.2 row 2)")
    void reauthRequiredNotifiesAdminAndFinancier() {
        emitter.onReauthRequired(new ReauthRequiredEvent(CONNECTION_ID));

        verifyAdminAndFinancierOnly("notification.bank.reauthRequired", "/bank/connections?connectionId=5");
    }

    @Test
    @DisplayName("syncFailed (claimed) notifies ADMIN + FINANCIER with the sync-runs deep-link (R22.2 row 3)")
    void syncFailedNotifiesAdminAndFinancier() {
        when(throttle.claimSyncFailed(CONNECTION_ID)).thenReturn(true);

        emitter.onSyncFailed(new SyncFailedEvent(CONNECTION_ID, 77L));

        verifyAdminAndFinancierOnly("notification.bank.syncFailed", "/bank/sync-runs?connectionId=5");
    }

    @Test
    @DisplayName("syncFailed within the 24 h window sends nothing")
    void syncFailedThrottledIsSkipped() {
        when(throttle.claimSyncFailed(CONNECTION_ID)).thenReturn(false);

        emitter.onSyncFailed(new SyncFailedEvent(CONNECTION_ID, 77L));

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("balanceGap notifies ADMIN + FINANCIER with the accounts deep-link (R22.2 row 4)")
    void balanceGapNotifiesAdminAndFinancier() {
        emitter.onBalanceGap(new BalanceGapEvent(9L, 100L));

        verifyAdminAndFinancierOnly("notification.bank.balanceGap", "/bank/accounts?accountId=9");
    }

    @Test
    @DisplayName("sync finished with to-review rows notifies ADMIN + FINANCIER + MANAGER (R22.2 row 5)")
    void transactionsToReviewNotifiesThreeRoles() {
        emitter.onSyncFinished(new BankSyncFinishedEvent(77L, CONNECTION_ID, 3));

        String type = "notification.bank.transactionsToReview";
        String link = "/company/bank-transactions?status=TO_REVIEW";
        verify(notificationService).create(eq(ADMIN_UID), eq(type), isNull(), eq(link));
        verify(notificationService).create(eq(FINANCIER_UID), eq(type), isNull(), eq(link));
        verify(notificationService).create(eq(MANAGER_UID), eq(type), isNull(), eq(link));
        verify(notificationService, times(3)).create(anyLong(), anyString(), isNull(), anyString());
    }

    @Test
    @DisplayName("sync finished with zero to-review rows sends nothing")
    void syncFinishedWithoutToReviewIsSilent() {
        emitter.onSyncFinished(new BankSyncFinishedEvent(77L, CONNECTION_ID, 0));

        verifyNoInteractions(notificationService, userDao);
    }

    @Test
    @DisplayName("a user holding several target roles is notified once (de-duplicated)")
    void recipientsAreDeduplicated() {
        when(userDao.findByRoleCode("MANAGER")).thenReturn(List.of(user(MANAGER_UID, true), user(ADMIN_UID, true)));

        emitter.onSyncFinished(new BankSyncFinishedEvent(77L, CONNECTION_ID, 1));

        verify(notificationService, times(1)).create(eq(ADMIN_UID), anyString(), isNull(), anyString());
        verify(notificationService, times(3)).create(anyLong(), anyString(), isNull(), anyString());
    }

    @Test
    @DisplayName("null events / ids are no-ops")
    void nullEventsAreNoOps() {
        assertThatCode(() -> {
            emitter.onConsentExpiring(null);
            emitter.onConsentExpiring(new ConsentExpiringEvent(null, "c"));
            emitter.onReauthRequired(null);
            emitter.onSyncFailed(null);
            emitter.onBalanceGap(null);
            emitter.onBalanceGap(new BalanceGapEvent(null, 1L));
            emitter.onSyncFinished(null);
        }).doesNotThrowAnyException();

        verifyNoInteractions(notificationService, throttle);
    }

    @Test
    @DisplayName("a failing create is logged and does not starve the other recipients (R22.6)")
    void failingCreateIsSwallowed() {
        doThrow(new RuntimeException("boom"))
                .when(notificationService).create(eq(ADMIN_UID), anyString(), isNull(), anyString());

        assertThatCode(() -> emitter.onReauthRequired(new ReauthRequiredEvent(CONNECTION_ID)))
                .doesNotThrowAnyException();

        verify(notificationService).create(eq(FINANCIER_UID), anyString(), isNull(), anyString());
    }

    @Test
    @DisplayName("a failing throttle claim or recipient lookup is logged and swallowed (R22.6)")
    void failingClaimOrLookupIsSwallowed() {
        when(throttle.claimSyncFailed(CONNECTION_ID)).thenThrow(new RuntimeException("db down"));
        when(userDao.findByRoleCode("ADMIN")).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> {
            emitter.onSyncFailed(new SyncFailedEvent(CONNECTION_ID, 1L));
            emitter.onBalanceGap(new BalanceGapEvent(9L, 1L));
        }).doesNotThrowAnyException();

        verifyNoInteractions(notificationService);
    }
}
