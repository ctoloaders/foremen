package com.foremen.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.BankFeedProviderStateDao;
import com.foremen.dao.NotificationDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.BankFeedProviderStateEntity;
import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.NotificationEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.bank.sync.BankConsentSweep;

/**
 * FOR-07-04a task 4.2 — the Fintable token-expiry notification through the real consent sweep, the
 * persisted markers in {@code bank_feed_provider_state}, the event listener and the notification table
 * (Requirement 12). With {@code token-expires-on = today + 5} the level is {@code 7}:
 * <ol>
 *   <li>the first sweep notifies every active ADMIN and FINANCIER once (body {@code null}, deep link
 *       {@code /bank/connections}) and stores marker level 7; an inactive user and a MANAGER get nothing;</li>
 *   <li>a second sweep sends nothing (once per level);</li>
 *   <li>a changed stored expiry date resets the marker, so the next sweep notifies again;</li>
 *   <li>a lower level than the stored one (30 → 7) notifies again; an equal one does not.</li>
 * </ol>
 *
 * <p>Repeatability: a fresh container per run; recipients are created per run with a run id and counted per
 * user, so seeded users never influence the assertions.
 *
 * <p>Validates: Requirements 16.3, 16.6 (R12.1–R12.4)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false",
        "foremen.bank-feed.stub.enabled=false",
        "foremen.bank-feed.saltedge.enabled=false",
        "foremen.bank-feed.fintable.enabled=true",
        "foremen.bank-feed.fintable.allow-insecure-base-url=true",
        "foremen.bank-feed.fintable.base-url=http://127.0.0.1:1/api/v2",
        "foremen.bank-feed.fintable.api-token=fake-it-token-0123456789",
        "foremen.public-base-url=http://localhost:3000",
        "foremen.bank-feed.sync.cron=-",
        "foremen.bank-feed.consent.sweep-cron=-"
})
@ActiveProfiles("integration-test")
@Testcontainers
@Tag("Feature: FOR-07-04a-fintable-integration, task 4.2: token expiry notification")
class FintableTokenExpiryNotificationIntegrationTest {

    private static final String TYPE = "notification.bank.fintableTokenExpiring";
    private static final LocalDate EXPIRES_ON = LocalDate.now().plusDays(5);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withUrlParam("stringtype", "unspecified");

    private static boolean migrated;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        synchronized (FintableTokenExpiryNotificationIntegrationTest.class) {
            if (!migrated) {
                BankLedgerItSupport.migrate(POSTGRES);
                migrated = true;
            }
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("foremen.bank-feed.fintable.token-expires-on", EXPIRES_ON::toString);
    }

    @Autowired private BankConsentSweep sweep;
    @Autowired private BankFeedProviderStateDao stateDao;
    @Autowired private NotificationDao notificationDao;
    @Autowired private UserDao userDao;
    @Autowired private RoleDao roleDao;
    @Autowired private PlatformTransactionManager transactionManager;

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT);

    @Test
    @DisplayName("sweep notifies ADMIN + FINANCIER once per level; a changed date resets the marker; a lower level notifies again")
    void tokenExpiryMarkers() {
        UserEntity admin = user("ADMIN", true);
        UserEntity financier = user("FINANCIER", true);
        UserEntity inactiveFinancier = user("FINANCIER", false);
        UserEntity manager = user("MANAGER", true);

        // --- first sweep: level 7, both recipients once
        sweep.sweep();
        assertThat(count(admin)).isEqualTo(1);
        assertThat(count(financier)).isEqualTo(1);
        assertThat(count(inactiveFinancier)).as("inactive users are skipped").isZero();
        assertThat(count(manager)).as("MANAGER is not a recipient").isZero();
        NotificationEntity sent = notifications(financier).get(0);
        assertThat(sent.getBody()).isNull();
        assertThat(sent.getDeepLink()).isEqualTo("/bank/connections");
        assertThat(sent.isRead()).isFalse();
        BankFeedProviderStateEntity state = stateDao.findById(FeedProvider.FINTABLE).orElseThrow();
        assertThat(state.getTokenExpiresOn()).isEqualTo(EXPIRES_ON);
        assertThat(state.getTokenNotifiedLevel()).isEqualTo(7);

        // --- same level again: nothing new
        sweep.sweep();
        assertThat(count(admin)).isEqualTo(1);
        assertThat(count(financier)).isEqualTo(1);

        // --- a different stored date (the configured one changed) resets the marker → notified again
        setState(EXPIRES_ON.minusDays(100), 0);
        sweep.sweep();
        assertThat(count(admin)).isEqualTo(2);
        assertThat(count(financier)).isEqualTo(2);
        state = stateDao.findById(FeedProvider.FINTABLE).orElseThrow();
        assertThat(state.getTokenExpiresOn()).isEqualTo(EXPIRES_ON);
        assertThat(state.getTokenNotifiedLevel()).isEqualTo(7);

        // --- a higher stored level (30) → the lower level 7 is new → notified again
        setState(EXPIRES_ON, 30);
        sweep.sweep();
        assertThat(count(admin)).isEqualTo(3);
        assertThat(count(financier)).isEqualTo(3);
        assertThat(stateDao.findById(FeedProvider.FINTABLE).orElseThrow().getTokenNotifiedLevel()).isEqualTo(7);

        // --- an equal stored level: nothing new
        sweep.sweep();
        assertThat(count(admin)).isEqualTo(3);
        assertThat(count(financier)).isEqualTo(3);
    }

    // ------------------------------------------------------------------ helpers

    private void setState(LocalDate expiresOn, Integer level) {
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            BankFeedProviderStateEntity state = stateDao.findById(FeedProvider.FINTABLE).orElseThrow();
            state.setTokenExpiresOn(expiresOn);
            state.setTokenNotifiedLevel(level);
            stateDao.save(state);
        });
    }

    private List<NotificationEntity> notifications(UserEntity user) {
        return notificationDao.findByRecipientIdOrderByCreatedDateDesc(user.getId()).stream()
                .filter(n -> TYPE.equals(n.getType())).toList();
    }

    private long count(UserEntity user) {
        return notifications(user).size();
    }

    private int seq;

    private UserEntity user(String roleCode, boolean active) {
        int n = ++seq;
        return new TransactionTemplate(transactionManager).execute(s -> {
            UserEntity u = new UserEntity();
            u.setName("Fintable expiry " + roleCode + " " + n);
            u.setEmail("fintable-expiry+" + runId + "-" + n + "@example.com");
            u.setRole(roleDao.findByCode(roleCode).orElseThrow());
            u.setActive(active);
            return userDao.save(u);
        });
    }
}
