package com.foremen.dao.integration;

import com.foremen.dao.OtpTokenDao;
import com.foremen.dao.model.OtpTokenEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DAO-level integration test for {@link OtpTokenDao}, exercising the real repository queries against
 * a real PostgreSQL instance (Testcontainers). It complements
 * {@code OtpTokenMigrationIntegrationTest} (which verifies the {@code 016} migration and the mapped
 * entity's NOT NULL / audit columns) by driving the two production query methods the OTP flow relies
 * on:
 *
 * <ul>
 *     <li>{@code findFirstByEmailIgnoreCaseAndUsedFalseAndExpiresAtAfterOrderByCreatedDateDesc}
 *         returns the newest unused, unexpired row for the email and skips used / expired rows and
 *         rows for another email (Requirement 3.1);</li>
 *     <li>{@code countByEmailIgnoreCaseAndCreatedDateAfter} counts only the rows for that email
 *         (case-insensitively) whose {@code createdDate} falls inside the trailing window, driving
 *         the rate-limit check (Requirement 3.2).</li>
 * </ul>
 *
 * <p>Mirrors the {@code @SpringBootTest} + Testcontainers + {@code @ActiveProfiles("integration-test")}
 * convention of {@code ProjectMemberDaoIntegrationTest}: Hibernate {@code create-drop} builds the
 * schema from the mapped entities, and every row is seeded under a unique {@code run-id} email suffix
 * so the suite re-runs without manual clean-up.
 *
 * <p>Because {@code created_date} is populated by JPA auditing on first persist and cannot be set
 * through the entity setter, rows that need a specific age (to make ordering deterministic and to
 * place rows inside/outside the rate-limit window) are back-dated with a direct JDBC UPDATE after
 * they are saved — the same JDBC approach {@code OtpTokenMigrationIntegrationTest} uses. The
 * back-dated value and the {@code countBy...After} cutoff are both wall-clock {@link LocalDateTime}s,
 * matching the {@code createdDate} field type populated by auditing.
 *
 * <p>Validates: Requirements 3.1, 3.2
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
class OtpTokenDaoIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            .withUrlParam("stringtype", "unspecified");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private OtpTokenDao otpTokenDao;

    /** Unique per-run emails so seeded rows never collide across repeated runs. */
    private String email;
    private String otherEmail;

    @BeforeEach
    void setUp() {
        long runId = System.nanoTime();
        email = "otp-dao+" + runId + "@example.com";
        otherEmail = "otp-dao-other+" + runId + "@example.com";
    }

    @Test
    @DisplayName("findFirst...UsedFalseAndExpiresAtAfter...OrderByCreatedDateDesc returns the newest "
            + "unused, unexpired row for the email (3.1)")
    void findsNewestUnusedUnexpiredRow() {
        Instant now = Instant.now();
        LocalDateTime nowLocal = LocalDateTime.now();

        // An older unused, unexpired row for the email (created 10 minutes ago).
        saveToken(email, "111111", now.plus(15, ChronoUnit.MINUTES), false, 0,
                nowLocal.minusMinutes(10));
        // The newest unused, unexpired row for the email (created 1 minute ago) — the expected hit.
        OtpTokenEntity newest = saveToken(email, "222222", now.plus(15, ChronoUnit.MINUTES), false, 0,
                nowLocal.minusMinutes(1));

        // Noise that must be excluded by the query:
        // - a newer but USED row for the same email,
        saveToken(email, "333333", now.plus(15, ChronoUnit.MINUTES), true, 0,
                nowLocal.plusMinutes(1));
        // - a newer but EXPIRED row for the same email,
        saveToken(email, "444444", now.minus(1, ChronoUnit.MINUTES), false, 0,
                nowLocal.plusMinutes(2));
        // - a newer valid row for a DIFFERENT email.
        saveToken(otherEmail, "555555", now.plus(15, ChronoUnit.MINUTES), false, 0,
                nowLocal.plusMinutes(3));

        Optional<OtpTokenEntity> found = otpTokenDao
                .findFirstByEmailIgnoreCaseAndUsedFalseAndExpiresAtAfterOrderByCreatedDateDesc(email, now);

        assertThat(found)
                .as("the newest unused, unexpired row for the email is returned")
                .isPresent();
        assertThat(found.get().getId()).isEqualTo(newest.getId());
        assertThat(found.get().getCode()).isEqualTo("222222");
    }

    @Test
    @DisplayName("findFirst...UsedFalseAndExpiresAtAfter... matches the email case-insensitively (3.1)")
    void lookupIsCaseInsensitive() {
        Instant now = Instant.now();
        OtpTokenEntity token = saveToken(email, "246810", now.plus(15, ChronoUnit.MINUTES), false, 0,
                LocalDateTime.now().minusMinutes(1));

        Optional<OtpTokenEntity> found = otpTokenDao
                .findFirstByEmailIgnoreCaseAndUsedFalseAndExpiresAtAfterOrderByCreatedDateDesc(
                        email.toUpperCase(), now);

        assertThat(found).as("uppercase email still matches the stored row").isPresent();
        assertThat(found.get().getId()).isEqualTo(token.getId());
    }

    @Test
    @DisplayName("findFirst...UsedFalseAndExpiresAtAfter... returns empty when only used/expired rows "
            + "exist for the email (3.1)")
    void returnsEmptyWhenNoUsableRow() {
        Instant now = Instant.now();
        LocalDateTime nowLocal = LocalDateTime.now();
        // Only a used row and an expired row exist — neither is eligible.
        saveToken(email, "111111", now.plus(15, ChronoUnit.MINUTES), true, 0,
                nowLocal.minusMinutes(2));
        saveToken(email, "222222", now.minus(1, ChronoUnit.MINUTES), false, 0,
                nowLocal.minusMinutes(1));

        Optional<OtpTokenEntity> found = otpTokenDao
                .findFirstByEmailIgnoreCaseAndUsedFalseAndExpiresAtAfterOrderByCreatedDateDesc(email, now);

        assertThat(found).as("no unused, unexpired row means an empty result").isEmpty();
    }

    @Test
    @DisplayName("countByEmailIgnoreCaseAndCreatedDateAfter counts only the email's rows inside the "
            + "window (3.2)")
    void countsRowsInWindowForEmail() {
        Instant now = Instant.now();
        LocalDateTime nowLocal = LocalDateTime.now();
        LocalDateTime cutoff = nowLocal.minusHours(1);

        // Three rows for the email inside the trailing hour.
        saveToken(email, "100001", now.plus(15, ChronoUnit.MINUTES), false, 0,
                nowLocal.minusMinutes(5));
        saveToken(email, "100002", now.plus(15, ChronoUnit.MINUTES), false, 0,
                nowLocal.minusMinutes(20));
        saveToken(email, "100003", now.plus(15, ChronoUnit.MINUTES), false, 0,
                nowLocal.minusMinutes(59));

        // One row for the email OUTSIDE the window (created 2 hours ago) — must not be counted.
        saveToken(email, "100004", now.plus(15, ChronoUnit.MINUTES), false, 0,
                nowLocal.minusHours(2));

        // A row for a DIFFERENT email inside the window — must not be counted.
        saveToken(otherEmail, "200001", now.plus(15, ChronoUnit.MINUTES), false, 0,
                nowLocal.minusMinutes(3));

        long count = otpTokenDao.countByEmailIgnoreCaseAndCreatedDateAfter(email, cutoff);

        assertThat(count)
                .as("only the three in-window rows for the email are counted")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("countByEmailIgnoreCaseAndCreatedDateAfter counts case-insensitively (3.2)")
    void countIsCaseInsensitive() {
        Instant now = Instant.now();
        LocalDateTime nowLocal = LocalDateTime.now();
        LocalDateTime cutoff = nowLocal.minusHours(1);

        saveToken(email, "100001", now.plus(15, ChronoUnit.MINUTES), false, 0,
                nowLocal.minusMinutes(5));
        saveToken(email, "100002", now.plus(15, ChronoUnit.MINUTES), false, 0,
                nowLocal.minusMinutes(10));

        long count = otpTokenDao.countByEmailIgnoreCaseAndCreatedDateAfter(email.toUpperCase(), cutoff);

        assertThat(count)
                .as("uppercase email still counts the two in-window rows")
                .isEqualTo(2);
    }

    // ---------------------------------------------------------------------
    // seeding helpers (unique per run via the email suffix)
    // ---------------------------------------------------------------------

    /**
     * Persists an {@link OtpTokenEntity} through the DAO, then back-dates its audited
     * {@code created_date} to {@code createdAt} with a direct JDBC UPDATE (the setter cannot override
     * the {@code @CreatedDate}-populated value). This makes ordering and window membership deterministic.
     */
    private OtpTokenEntity saveToken(String tokenEmail, String code, Instant expiresAt, boolean used,
                                     int attempts, LocalDateTime createdAt) {
        OtpTokenEntity token = new OtpTokenEntity();
        token.setEmail(tokenEmail);
        token.setCode(code);
        token.setExpiresAt(expiresAt);
        token.setUsed(used);
        token.setAttempts(attempts);
        OtpTokenEntity saved = otpTokenDao.save(token);
        backdateCreatedDate(saved.getId(), createdAt);
        return saved;
    }

    private void backdateCreatedDate(Long id, LocalDateTime createdAt) {
        String sql = "UPDATE otp_tokens SET created_date = ? WHERE id = ?";
        try (Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, createdAt);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to back-date created_date for otp_tokens id " + id, e);
        }
    }
}
