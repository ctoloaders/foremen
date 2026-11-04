package com.foremen.service.bank.notify;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.foremen.dao.UserDao;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.offer.NotificationService;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-07-04 (Requirement 22; design §Notifications) — the best-effort consumer that turns the five
 * bank-feed events into in-app notifications through the generic FOR-05-07
 * {@link NotificationService} (emit only, no own delivery — R22.1). Mirrors
 * {@code PaymentNotificationEmitter}, but resolves recipients by <b>company role</b>
 * ({@link UserDao#findByRoleCode}, active users only, de-duplicated) instead of project membership.
 *
 * <table>
 *   <caption>Requirement 22.2 notification map</caption>
 *   <tr><th>Event</th><th>Type (i18n key)</th><th>Recipients</th><th>Deep-link</th></tr>
 *   <tr><td>{@link ConsentExpiringEvent} (once per consent)</td><td>{@link #TYPE_CONSENT_EXPIRING}</td><td>ADMIN, FINANCIER</td><td>{@code /bank/connections?connectionId={id}}</td></tr>
 *   <tr><td>{@link ReauthRequiredEvent}</td><td>{@link #TYPE_REAUTH_REQUIRED}</td><td>ADMIN, FINANCIER</td><td>{@code /bank/connections?connectionId={id}}</td></tr>
 *   <tr><td>{@link SyncFailedEvent} (≤ 1 / connection / 24 h)</td><td>{@link #TYPE_SYNC_FAILED}</td><td>ADMIN, FINANCIER</td><td>{@code /bank/sync-runs?connectionId={id}}</td></tr>
 *   <tr><td>{@link BalanceGapEvent}</td><td>{@link #TYPE_BALANCE_GAP}</td><td>ADMIN, FINANCIER</td><td>{@code /bank/accounts?accountId={id}}</td></tr>
 *   <tr><td>{@link BankSyncFinishedEvent} with {@code toReviewCount > 0}</td><td>{@link #TYPE_TRANSACTIONS_TO_REVIEW}</td><td>ADMIN, FINANCIER, MANAGER</td><td>{@code /company/bank-transactions?status=TO_REVIEW}</td></tr>
 * </table>
 *
 * <p>Throttle markers (syncFailed 24 h, consentExpiring once per consent) are claimed and persisted by
 * {@link BankNotificationThrottle} in its own {@code REQUIRES_NEW} transaction before sending.
 *
 * <p>Cash entries and {@code Holder_Match} changes have <b>no</b> event here (R22.5); client-payment
 * and invoice-upload links rely on the existing FOR-06-07 / FOR-06-03 notifications (R22.3, R22.4).
 *
 * <h2>Best-effort (R22.6)</h2>
 * Every listener is {@code AFTER_COMMIT} ({@code fallbackExecution = true} for publishes outside a
 * transaction), so it can never roll back the triggering change; any failure — recipient resolution,
 * throttle claim, or a single recipient's {@code create} — is logged and swallowed, and one failed
 * recipient never starves the others.
 *
 * <p>The {@code type} values are pure i18n keys resolved on the frontend ({@code notification.bank.*},
 * task 9.1); no backend message entry is required.
 */
@Component
@Slf4j
public class BankNotificationEmitter {

    static final String TYPE_CONSENT_EXPIRING = "notification.bank.consentExpiring";
    static final String TYPE_REAUTH_REQUIRED = "notification.bank.reauthRequired";
    static final String TYPE_SYNC_FAILED = "notification.bank.syncFailed";
    static final String TYPE_BALANCE_GAP = "notification.bank.balanceGap";
    static final String TYPE_TRANSACTIONS_TO_REVIEW = "notification.bank.transactionsToReview";

    static final String ADMIN_ROLE = "ADMIN";
    static final String FINANCIER_ROLE = "FINANCIER";
    static final String MANAGER_ROLE = "MANAGER";

    static final String TO_REVIEW_DEEP_LINK = "/company/bank-transactions?status=TO_REVIEW";

    private final NotificationService notificationService;
    private final UserDao userDao;
    private final BankNotificationThrottle throttle;

    public BankNotificationEmitter(NotificationService notificationService,
                                   UserDao userDao,
                                   BankNotificationThrottle throttle) {
        this.notificationService = notificationService;
        this.userDao = userDao;
        this.throttle = throttle;
    }

    /** R22.2 row 1: CONSENT_EXPIRING, once per consent ⇒ ADMIN + FINANCIER. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onConsentExpiring(ConsentExpiringEvent event) {
        if (event == null || event.connectionId() == null) {
            return;
        }
        if (!claim(TYPE_CONSENT_EXPIRING, event.connectionId(),
                () -> throttle.claimConsentExpiring(event.connectionId(), event.consentId()))) {
            return;
        }
        notifyRoles(TYPE_CONSENT_EXPIRING, connectionsLink(event.connectionId()), ADMIN_ROLE, FINANCIER_ROLE);
    }

    /** R22.2 row 2: REAUTH_REQUIRED ⇒ ADMIN + FINANCIER. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onReauthRequired(ReauthRequiredEvent event) {
        if (event == null || event.connectionId() == null) {
            return;
        }
        notifyRoles(TYPE_REAUTH_REQUIRED, connectionsLink(event.connectionId()), ADMIN_ROLE, FINANCIER_ROLE);
    }

    /** R22.2 row 3: a sync run FAILED, throttled to 1 per connection per 24 h ⇒ ADMIN + FINANCIER. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSyncFailed(SyncFailedEvent event) {
        if (event == null || event.connectionId() == null) {
            return;
        }
        if (!claim(TYPE_SYNC_FAILED, event.connectionId(),
                () -> throttle.claimSyncFailed(event.connectionId()))) {
            return;
        }
        notifyRoles(TYPE_SYNC_FAILED, "/bank/sync-runs?connectionId=" + event.connectionId(),
                ADMIN_ROLE, FINANCIER_ROLE);
    }

    /** R22.2 row 4: a balance gap was recorded ⇒ ADMIN + FINANCIER. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBalanceGap(BalanceGapEvent event) {
        if (event == null || event.accountId() == null) {
            return;
        }
        notifyRoles(TYPE_BALANCE_GAP, "/bank/accounts?accountId=" + event.accountId(),
                ADMIN_ROLE, FINANCIER_ROLE);
    }

    /** R22.2 row 5: a sync run ingested ≥ 1 new posted transaction in TO_REVIEW ⇒ ADMIN + FINANCIER + MANAGER. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSyncFinished(BankSyncFinishedEvent event) {
        if (event == null || event.toReviewCount() <= 0) {
            return;
        }
        notifyRoles(TYPE_TRANSACTIONS_TO_REVIEW, TO_REVIEW_DEEP_LINK, ADMIN_ROLE, FINANCIER_ROLE, MANAGER_ROLE);
    }

    // --- throttle + recipients + emission ---

    private interface Claim {
        boolean run();
    }

    /** Runs a throttle claim; a failure is logged and treated as "not claimed" (no send, R22.6). */
    private boolean claim(String type, Long connectionId, Claim claim) {
        try {
            return claim.run();
        } catch (Exception e) {
            log.error("Failed to claim bank notification throttle (type {}) for connection {}",
                    type, connectionId, e);
            return false;
        }
    }

    /** Sends {@code type} to every active user of any of {@code roleCodes}, each user at most once. */
    private void notifyRoles(String type, String deepLink, String... roleCodes) {
        Set<Long> recipients;
        try {
            recipients = resolveRecipients(roleCodes);
        } catch (Exception e) {
            log.error("Failed to resolve recipients for bank notification (type {})", type, e);
            return;
        }
        for (Long userId : recipients) {
            try {
                notificationService.create(userId, type, null, deepLink);
            } catch (Exception e) {
                log.error("Failed to emit bank notification (type {}) to user {}", type, userId, e);
            }
        }
    }

    private Set<Long> resolveRecipients(String... roleCodes) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String roleCode : roleCodes) {
            List<UserEntity> users = userDao.findByRoleCode(roleCode);
            if (users == null) {
                continue;
            }
            for (UserEntity user : users) {
                if (user != null && user.isActive() && user.getId() != null) {
                    ids.add(user.getId());
                }
            }
        }
        return ids;
    }

    private static String connectionsLink(Long connectionId) {
        return "/bank/connections?connectionId=" + connectionId;
    }
}
