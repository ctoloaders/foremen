package com.foremen.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.security.JwtProperties;
import com.foremen.dao.OtpTokenDao;
import com.foremen.dao.RefreshTokenDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.OtpTokenEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.service.mail.OtpMailSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * End-to-end and rate-limit-durability integration test for the FOR-03-05 OTP client-login flow
 * (task 13.1).
 *
 * <p>Boots the full application context against a Testcontainers PostgreSQL instance (following the
 * project's established {@code @SpringBootTest} + {@code @Testcontainers} +
 * {@code @DynamicPropertySource} pattern, mirroring
 * {@link InviteEndToEndIntegrationTest}) and drives the real HTTP stack ({@link MockMvc}), the real
 * {@code OtpService}/{@code AuthService}/{@code OtpTokenDao} layer, and a real PostgreSQL schema
 * (Hibernate {@code create-drop} under the {@code integration-test} profile, which disables
 * Liquibase — so the CLIENT role and the seeded ACTIVE CLIENT user are created programmatically via
 * the DAOs).
 *
 * <p>The {@link OtpMailSender} is the only mocked collaborator: it is replaced with a Mockito mock so
 * the flow neither reaches SMTP nor requires a mail server, while its dispatch count remains
 * verifiable and the emailed code is captured via an {@link ArgumentCaptor} (the code is never
 * exposed in an HTTP response — Silent_Success).
 *
 * <p>Covered flows:
 * <ol>
 *   <li><b>Happy path (4.3, 6.3, 6.9, 6.10):</b> seed an ACTIVE CLIENT &rarr;
 *       {@code POST /api/auth/otp/request} &rarr; exactly one {@code otp_tokens} row persisted and
 *       exactly one email captured &rarr; {@code POST /api/auth/otp/verify} with the captured code
 *       &rarr; HTTP 200 with a non-blank access/refresh pair, {@code expiresIn} equal to
 *       {@code clientAccessTtlMinutes * 60}, and the token flipped to {@code used} &rarr; re-verify
 *       the same (now consumed) code &rarr; HTTP 400 {@code error.auth.otp.invalid}.</li>
 *   <li><b>Rate-limit durability (5.4):</b> insert five {@code otp_tokens} rows for an email
 *       directly (simulating five prior requests that survived across restarts, since the count is
 *       a pure DB query with no in-memory counter) &rarr; a fresh {@code POST /api/auth/otp/request}
 *       for that email &rarr; HTTP 429 {@code error.auth.otp.rate.limited} with no new row
 *       persisted, proving the count-based limit still applies across a fresh application
 *       context.</li>
 * </ol>
 *
 * <p>Repeatability: every seeded user/email uses a per-test UUID plus a static counter
 * ({@code otp-e2e+{uuid}-{n}@example.com}), and {@link #cleanUp()} removes all rows the flow can
 * create (otp tokens, refresh tokens, users, roles) after each test, so the suite re-runs without
 * manual DB clean-up.
 *
 * <p>Validates: Requirements 4.3, 5.4, 6.3, 6.9, 6.10
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
class OtpEndToEndIntegrationTest {

    private static final String OTP_REQUEST_PATH = "/api/auth/otp/request";
    private static final String OTP_VERIFY_PATH = "/api/auth/otp/verify";

    // Exact string from messages.properties (PL base) for error.auth.otp.invalid, asserted so the
    // resolved 400 body confirms the correct message code was raised on re-verify.
    private static final String PL_OTP_INVALID = "Nieprawidłowy kod jednorazowy.";

    private static final AtomicLong COUNTER = new AtomicLong();

    /** Far-past cutoff so the rate-limit count query returns every row for an email (test-only). */
    private static final LocalDateTime EPOCH_CUTOFF = LocalDateTime.of(1970, 1, 1, 0, 0);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            // stringtype=unspecified lets PostgreSQL implicitly cast the UserEntity JSON converter's
            // text value into the jsonb display_preferences column (matching the deployed app).
            .withUrlParam("stringtype", "unspecified");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    /** Local instance — the MOCK web-environment context does not expose an ObjectMapper bean. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoleDao roleDao;

    @Autowired
    private UserDao userDao;

    @Autowired
    private OtpTokenDao otpTokenDao;

    @Autowired
    private RefreshTokenDao refreshTokenDao;

    @Autowired
    private JwtProperties jwtProperties;

    /** Only mocked collaborator: keeps the flow off SMTP and lets us capture the emailed code. */
    @MockitoBean
    private OtpMailSender otpMailSender;

    /** Unique run id so emails/role codes never collide with earlier runs. */
    private final String runId = UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        // FK order: token tables (otp + refresh, refresh -> users) before users, users (-> roles)
        // before roles. A successful verify auto-logins and issues a refresh token, so clear it too.
        otpTokenDao.deleteAll();
        refreshTokenDao.deleteAll();
        userDao.deleteAll();
        roleDao.deleteAll();
    }

    // === 4.3 / 6.3 / 6.9 / 6.10 : full OTP login lifecycle ===

    @Test
    @DisplayName("Full OTP flow: request -> one code + one email -> verify issues client-TTL tokens -> reuse rejected")
    void otpLifecycle_requestVerifyAndReuse() throws Exception {
        RoleEntity clientRole = persistClientRole();
        String email = uniqueEmail();
        seedActiveClient(email, clientRole);

        // 1) POST /api/auth/otp/request -> 200 (no body distinction; Silent_Success).
        MvcResult requestResult = mockMvc.perform(post(OTP_REQUEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpRequestBody(email))))
                .andReturn();

        assertThat(requestResult.getResponse().getStatus())
                .as("otp/request for an eligible email must return 200")
                .isEqualTo(200);

        // Exactly one otp_tokens row persisted for the eligible email (Requirement 4.3). Counted
        // via the rate-limit query with an epoch cutoff so it captures every row for the email.
        long tokenCount = otpTokenDao.countByEmailIgnoreCaseAndCreatedDateAfter(email, EPOCH_CUTOFF);
        assertThat(tokenCount)
                .as("exactly one OTP token must be persisted on request for an eligible email")
                .isEqualTo(1);

        OtpTokenEntity persisted = otpTokenDao
                .findFirstByEmailIgnoreCaseOrderByCreatedDateDesc(email)
                .orElseThrow();
        assertThat(persisted.isUsed()).as("freshly issued code is not used").isFalse();
        assertThat(persisted.getAttempts()).as("freshly issued code has zero attempts").isZero();
        assertThat(persisted.getCode()).as("persisted code is exactly 6 digits").matches("\\d{6}");

        // Exactly one email dispatched; capture the code that was emailed (never in the HTTP body).
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(otpMailSender, times(1)).send(any(UserEntity.class), codeCaptor.capture());
        String emailedCode = codeCaptor.getValue();
        assertThat(emailedCode)
                .as("the emailed code must be the persisted code")
                .isEqualTo(persisted.getCode());

        // 2) POST /api/auth/otp/verify with the emailed code -> 200 with client-TTL tokens.
        MvcResult verifyResult = mockMvc.perform(post(OTP_VERIFY_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpVerifyBody(email, emailedCode))))
                .andReturn();

        assertThat(verifyResult.getResponse().getStatus())
                .as("otp/verify with a valid code must return 200")
                .isEqualTo(200);

        JsonNode tokenResponse = objectMapper.readTree(verifyResult.getResponse().getContentAsString());
        assertThat(tokenResponse.path("accessToken").asText())
                .as("verify response must carry a non-blank access token")
                .isNotBlank();
        assertThat(tokenResponse.path("refreshToken").asText())
                .as("verify response must carry a non-blank refresh token")
                .isNotBlank();
        // (6.9) expiresIn equals the client access TTL in seconds (default 120 * 60 = 7200).
        long expectedExpiresIn = jwtProperties.clientAccessTtlMinutes() * 60L;
        assertThat(tokenResponse.path("expiresIn").asLong())
                .as("expiresIn must equal clientAccessTtlMinutes * 60")
                .isEqualTo(expectedExpiresIn);

        // (6.3) The code is marked used after a successful verification.
        OtpTokenEntity consumed = otpTokenDao.findById(persisted.getId()).orElseThrow();
        assertThat(consumed.isUsed())
                .as("consumed OTP code must be marked used after a successful verify")
                .isTrue();

        // 3) Re-verify the SAME (now consumed) code -> 400 error.auth.otp.invalid (6.10, 6.8).
        MvcResult reuseResult = mockMvc.perform(post(OTP_VERIFY_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpVerifyBody(email, emailedCode))))
                .andReturn();

        assertThat(reuseResult.getResponse().getStatus())
                .as("re-verifying a used code must return 400 (error.auth.otp.invalid)")
                .isEqualTo(400);
        JsonNode reuseBody = objectMapper.readTree(reuseResult.getResponse().getContentAsString());
        assertThat(reuseBody.path("message").asText())
                .as("reuse rejection message must be the resolved error.auth.otp.invalid text")
                .isEqualTo(PL_OTP_INVALID);

        // No second email was ever dispatched by the two verify calls.
        verify(otpMailSender, times(1)).send(any(UserEntity.class), eq(emailedCode));
    }

    // === 5.4 : the count-based rate limit is backed by otp_tokens and survives a fresh context ===

    @Test
    @DisplayName("Rate-limit durability: five pre-existing otp_tokens rows -> a fresh request is 429 with no new row")
    void rateLimit_isDbBackedAndDurableAcrossContext() throws Exception {
        RoleEntity clientRole = persistClientRole();
        String email = uniqueEmail();
        // Seed an eligible ACTIVE CLIENT so the 429 cannot be attributed to ineligibility — the
        // rate-limit check runs BEFORE the user lookup, so it must fire regardless of eligibility.
        seedActiveClient(email, clientRole);

        // Insert five otp_tokens rows directly (simulating five prior requests). Because the limit is
        // a pure DB count with no in-memory counter, these rows alone — present in a freshly booted
        // context — must trip the limit on the next request (Requirement 5.4).
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            OtpTokenEntity row = new OtpTokenEntity();
            row.setEmail(email);
            row.setCode(String.format("%06d", i));
            row.setExpiresAt(now.plus(15, ChronoUnit.MINUTES));
            row.setUsed(false);
            row.setAttempts(0);
            otpTokenDao.save(row);
        }

        long rowsBefore = otpTokenDao.countByEmailIgnoreCaseAndCreatedDateAfter(email, EPOCH_CUTOFF);
        assertThat(rowsBefore).as("five prior OTP rows seeded for the email").isEqualTo(5);

        // A fresh request for the same email must be rejected with 429 (>= 5 in the trailing hour).
        MvcResult limited = mockMvc.perform(post(OTP_REQUEST_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OtpRequestBody(email))))
                .andReturn();

        assertThat(limited.getResponse().getStatus())
                .as("the sixth request within the hour must be rate-limited with 429")
                .isEqualTo(429);
        JsonNode limitedBody = objectMapper.readTree(limited.getResponse().getContentAsString());
        assertThat(limitedBody.path("status").asInt()).isEqualTo(429);

        // The rate-limit branch fires before user lookup and persists no new row / sends no email.
        long rowsAfter = otpTokenDao.countByEmailIgnoreCaseAndCreatedDateAfter(email, EPOCH_CUTOFF);
        assertThat(rowsAfter)
                .as("a rate-limited request must persist no additional OTP row")
                .isEqualTo(rowsBefore);
        verify(otpMailSender, times(0)).send(any(UserEntity.class), any(String.class));
    }

    // === helpers ===

    private RoleEntity persistClientRole() {
        return roleDao.findByCode("CLIENT").orElseGet(() -> {
            RoleEntity role = new RoleEntity();
            role.setCode("CLIENT");
            role.setNameRU("Клиент");
            role.setNamePL("Klient");
            role.setSystem(true);
            return roleDao.save(role);
        });
    }

    private UserEntity seedActiveClient(String email, RoleEntity clientRole) {
        UserEntity user = new UserEntity();
        user.setName("OTP E2E Client");
        user.setEmail(email);
        user.setRole(clientRole);
        user.setStatus(UserStatus.ACTIVE);
        user.setActive(true);
        user.setLocale("ru");
        return userDao.save(user);
    }

    private String uniqueEmail() {
        return "otp-e2e+" + runId + "-" + COUNTER.incrementAndGet() + "@example.com";
    }

    // Request bodies (records mirror the API contract; avoids depending on internal DTO records).
    private record OtpRequestBody(String email) {}

    private record OtpVerifyBody(String email, String code) {}
}
