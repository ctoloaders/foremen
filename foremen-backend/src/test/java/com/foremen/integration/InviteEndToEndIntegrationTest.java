package com.foremen.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.InviteTokenDao;
import com.foremen.dao.RefreshTokenDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.InviteTokenEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.service.mail.InvitationMailSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * End-to-end integration test for the FOR-03-02 invite flow.
 *
 * <p>Boots the full application context against a Testcontainers PostgreSQL instance
 * (following the project's established {@code @SpringBootTest} + {@code @Testcontainers} +
 * {@code @DynamicPropertySource} pattern, mirroring
 * {@code InviteSecurityWiringIntegrationTest}) and exercises the complete invite lifecycle
 * through the real HTTP stack ({@link MockMvc}), the real service/DAO layer, and a real
 * PostgreSQL schema (Hibernate {@code create-drop} under the {@code integration-test} profile,
 * which disables Liquibase — so all fixture rows, including roles, are created programmatically
 * via the DAOs).
 *
 * <p>The {@link InvitationMailSender} is the only mocked collaborator: it is replaced with a
 * Mockito mock so the flow neither reaches SMTP nor requires a running mail server, while its
 * dispatch count remains verifiable.
 *
 * <p>Covered flow:
 * <ol>
 *   <li>{@code POST /api/users} (employee role) &rarr; exactly one invite token persisted and
 *       exactly one set-password email dispatched (3.1, 4.1).</li>
 *   <li>{@code POST /api/auth/set-password} with that token &rarr; HTTP 200 with a
 *       non-blank access/refresh token pair, the user flipped to {@code ACTIVE}, and the token
 *       marked {@code used} (5.4, 5.5).</li>
 *   <li>Re-submitting the same (now consumed) token &rarr; HTTP 400 {@code error.invite.token.used}
 *       (5.9).</li>
 *   <li>{@code POST /api/auth/resend-invite} as {@code ROLE_ADMIN} for a freshly created
 *       {@code INVITED} user &rarr; the prior unused token is invalidated and a new unused token
 *       is issued (token rotation, 6.6).</li>
 * </ol>
 *
 * <p>Repeatability: every created user uses a unique email built from a per-test UUID/counter
 * ({@code invite-e2e+{uuid}-{n}@example.com}), and {@link #cleanUp()} removes all rows created by
 * the test (invite tokens, users, roles) after each test, so the suite re-runs without manual DB
 * cleanup.
 *
 * <p>Validates: Requirements 3.1, 4.1, 5.4, 5.5, 5.9, 6.6
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
class InviteEndToEndIntegrationTest {

    private static final String USERS_PATH = "/api/users";
    private static final String SET_PASSWORD_PATH = "/api/auth/set-password";
    private static final String RESEND_INVITE_PATH = "/api/auth/resend-invite";

    private static final AtomicLong COUNTER = new AtomicLong();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            // stringtype=unspecified lets PostgreSQL implicitly cast the JSON converter's text value
            // into the jsonb display_preferences column (matching the deployed app's handling).
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
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private RoleDao roleDao;

    @Autowired
    private UserDao userDao;

    @Autowired
    private InviteTokenDao inviteTokenDao;

    @Autowired
    private RefreshTokenDao refreshTokenDao;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    /** Only mocked collaborator: keeps the flow off SMTP and lets us count dispatches. */
    @MockitoBean
    private InvitationMailSender invitationMailSender;

    /** Unique run id so emails/role codes never collide with earlier runs. */
    private final String runId = UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        // FK order: token tables (invite + refresh, both -> users) before users, users (-> roles)
        // before roles. Set-password auto-login issues a refresh token, so it must be cleared too.
        inviteTokenDao.deleteAll();
        refreshTokenDao.deleteAll();
        userDao.deleteAll();
        roleDao.deleteAll();
    }

    @Test
    @DisplayName("Full invite lifecycle: create -> one token + one email -> set-password activates -> reuse rejected")
    void inviteLifecycle_createSetPasswordAndReuse() throws Exception {
        RoleEntity employeeRole = persistRole("WORKER");
        String email = uniqueEmail();

        // 1) Create a user via POST /api/users (server hard-codes status = INVITED).
        long userId = createUser(email, employeeRole.getId());

        // Exactly one invite token persisted for the created user (Requirement 3.1).
        List<InviteTokenEntity> tokensForUser = inviteTokenDao.findByUserIdAndUsedFalse(userId);
        assertThat(tokensForUser)
                .as("exactly one unused invite token must be persisted on user create")
                .hasSize(1);

        // Exactly one set-password email dispatched for an employee role (Requirement 4.1).
        verify(invitationMailSender, times(1)).sendSetPasswordInvitation(any(UserEntity.class), anyString());
        verify(invitationMailSender, times(0)).sendClientPortalInvitation(any(UserEntity.class));

        UserEntity createdUser = userDao.findById(userId).orElseThrow();
        assertThat(createdUser.getStatus())
                .as("created user must be INVITED")
                .isEqualTo(UserStatus.INVITED);
        assertThat(createdUser.getPasswordHash())
                .as("created user must have no password yet")
                .isNull();

        String token = tokensForUser.get(0).getToken();

        // 2) POST /api/auth/set-password with the token -> 200 with tokens.
        String rawPassword = "Sup3rSecret!";
        MvcResult setPwdResult = mockMvc.perform(post(SET_PASSWORD_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SetPasswordBody(token, rawPassword))))
                .andReturn();

        assertThat(setPwdResult.getResponse().getStatus())
                .as("set-password with a valid token must return 200")
                .isEqualTo(200);

        JsonNode tokenResponse = objectMapper.readTree(setPwdResult.getResponse().getContentAsString());
        assertThat(tokenResponse.path("accessToken").asText())
                .as("response must carry a non-blank access token (auto-login)")
                .isNotBlank();
        assertThat(tokenResponse.path("refreshToken").asText())
                .as("response must carry a non-blank refresh token (auto-login)")
                .isNotBlank();
        assertThat(tokenResponse.path("expiresIn").asLong())
                .as("response must carry a positive expiresIn")
                .isPositive();

        // User flipped to ACTIVE with a bcrypt password hash (Requirement 5.4).
        UserEntity activated = userDao.findById(userId).orElseThrow();
        assertThat(activated.getStatus())
                .as("user must be ACTIVE after set-password")
                .isEqualTo(UserStatus.ACTIVE);
        assertThat(activated.getPasswordHash())
                .as("user must have a password hash after set-password")
                .isNotBlank();
        assertThat(passwordEncoder.matches(rawPassword, activated.getPasswordHash()))
                .as("stored hash must verify the supplied password")
                .isTrue();

        // Token marked used (Requirement 5.4, 5.5).
        InviteTokenEntity consumed = inviteTokenDao.findByToken(token).orElseThrow();
        assertThat(consumed.isUsed())
                .as("consumed invite token must be marked used")
                .isTrue();

        // 3) Re-submit the same token -> 400 error.invite.token.used (Requirement 5.9).
        MvcResult reuseResult = mockMvc.perform(post(SET_PASSWORD_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SetPasswordBody(token, rawPassword))))
                .andReturn();

        assertThat(reuseResult.getResponse().getStatus())
                .as("reusing a consumed token must return 400 (error.invite.token.used)")
                .isEqualTo(400);
        // The error pipeline surfaces the *resolved* message (not the raw code) in the body; with the
        // default (Polish) request locale that is the messages.properties value for
        // error.invite.token.used. Asserting the resolved text confirms the correct code was raised.
        JsonNode reuseBody = objectMapper.readTree(reuseResult.getResponse().getContentAsString());
        assertThat(reuseBody.path("message").asText())
                .as("reuse rejection message must be the resolved error.invite.token.used text")
                .isEqualTo("To zaproszenie zostało już wykorzystane.");
    }

    @Test
    @DisplayName("Resend-invite as ADMIN for an INVITED user rotates the token (old used, new unused)")
    void resendInvite_rotatesTokenForInvitedUser() throws Exception {
        RoleEntity employeeRole = persistRole("MANAGER");
        String email = uniqueEmail();

        long userId = createUser(email, employeeRole.getId());

        InviteTokenEntity original = inviteTokenDao.findByUserIdAndUsedFalse(userId).get(0);
        String originalToken = original.getToken();

        // Mint an ADMIN access token (JwtAuthenticationFilter maps role claim -> ROLE_ADMIN).
        String adminAccessToken = jwtTokenProvider.generateAccessToken(999_999L, "ADMIN", "admin+" + runId + "@example.com");

        MvcResult resendResult = mockMvc.perform(post(RESEND_INVITE_PATH)
                        .header("Authorization", "Bearer " + adminAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResendInviteBody(userId))))
                .andReturn();

        assertThat(resendResult.getResponse().getStatus())
                .as("resend-invite as ADMIN for an INVITED user must return 200")
                .isEqualTo(200);

        // Token rotation (Requirement 6.6): the original token is now used ...
        InviteTokenEntity rotatedOriginal = inviteTokenDao.findByToken(originalToken).orElseThrow();
        assertThat(rotatedOriginal.isUsed())
                .as("the previously outstanding token must be invalidated (used = true) on resend")
                .isTrue();

        // ... and exactly one fresh, unused token now exists for the user.
        List<InviteTokenEntity> unusedNow = inviteTokenDao.findByUserIdAndUsedFalse(userId);
        assertThat(unusedNow)
                .as("resend must leave exactly one unused token")
                .hasSize(1);
        assertThat(unusedNow.get(0).getToken())
                .as("the new token must differ from the rotated-out token")
                .isNotEqualTo(originalToken);

        // A second invitation email was dispatched (one on create, one on resend).
        verify(invitationMailSender, times(2)).sendSetPasswordInvitation(any(UserEntity.class), anyString());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private long createUser(String email, Long roleId) throws Exception {
        UserCreateBody body = new UserCreateBody("Invite E2E User", email, roleId, "ru");
        MvcResult result = mockMvc.perform(post(USERS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("POST /api/users must succeed (200)")
                .isEqualTo(200);

        JsonNode created = objectMapper.readTree(result.getResponse().getContentAsString());
        return created.path("id").asLong();
    }

    private RoleEntity persistRole(String baseCode) {
        RoleEntity role = new RoleEntity();
        role.setCode(baseCode + "_" + runId + "_" + COUNTER.incrementAndGet());
        role.setNameRU("Роль " + baseCode);
        role.setNamePL("Rola " + baseCode);
        role.setSystem(false);
        return roleDao.save(role);
    }

    private String uniqueEmail() {
        return "invite-e2e+" + runId + "-" + COUNTER.incrementAndGet() + "@example.com";
    }

    // Request bodies (records mirror the API contract; avoids depending on internal DTO records).
    private record UserCreateBody(String name, String email, Long roleId, String locale) {}

    private record SetPasswordBody(String token, String password) {}

    private record ResendInviteBody(Long userId) {}
}
