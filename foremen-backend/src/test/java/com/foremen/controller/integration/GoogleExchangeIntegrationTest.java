package com.foremen.controller.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.InviteTokenDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.InviteTokenEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for {@code POST /api/auth/google} (task 19.4) running the full Spring context —
 * including the Spring Security filter chain — against a real PostgreSQL database (Testcontainers)
 * with the Liquibase schema and seed data applied.
 *
 * <p>The one collaborator that would otherwise reach out to Google's certificate endpoint — the
 * {@link GoogleIdTokenVerifier} bean wired by {@code GoogleTokenVerifierConfig} — is replaced with a
 * {@code @MockitoBean}. Each test stubs {@code verify(...)} to either return a
 * {@link GoogleIdToken} whose payload reports a controllable verified email (driving the branch by
 * the looked-up account's status) or to return {@code null} (the invalid-token case). Everything
 * downstream of verification (email lookup, status branch, JWT issuance, set-password token
 * minting) runs for real against the seeded database.
 *
 * <p>Covered branches (Requirements 14.4, 14.5, 14.8, 14.9, 14.14):
 * <ol>
 *   <li>ACTIVE account &rarr; {@code 200} {@code AUTHENTICATED} with a non-null token pair and a
 *       {@code null} set-password token (14.4, 14.14).</li>
 *   <li>INVITED account &rarr; {@code 200} {@code ACTIVATION_REQUIRED} with null tokens and a
 *       non-blank set-password token, which a follow-up {@code POST /api/auth/set-password}
 *       accepts to activate the account and auto-login (14.5, 14.14).</li>
 *   <li>Unknown email &rarr; {@code 403} {@code error.auth.google.no.account} (14.9).</li>
 *   <li>DEACTIVATED account &rarr; {@code 403} {@code error.auth.account.deactivated} (14.8).</li>
 *   <li>Invalid token (verifier returns {@code null}) &rarr; {@code 401}
 *       {@code error.auth.google.token.invalid} (14.7 backend counterpart).</li>
 *   <li>An unauthenticated request reaches the controller (the endpoint is public under the
 *       {@code /api/auth/**} {@code permitAll} rule), so a bad token yields a business 401 with an
 *       {@code ErrorResponse} body — not a bare Spring Security 401.</li>
 * </ol>
 *
 * <p>The database schema and the built-in roles/permissions are provisioned by applying the full
 * Liquibase changelog against the Testcontainers PostgreSQL instance before the Spring context
 * starts (Spring Boot 4 does not auto-run Liquibase without the dedicated autoconfiguration module,
 * so the changelog is applied explicitly here — the same pattern used by
 * {@code MeConditionalRequestIntegrationTest} and {@code AuthFlowIntegrationTest}). Each run seeds
 * its own users with unique emails so the test is repeatable without manual cleanup.
 *
 * <p>Validates: Requirements 14.4, 14.5, 14.8, 14.9, 14.14
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
class GoogleExchangeIntegrationTest {

    private static final String GOOGLE_PATH = "/api/auth/google";
    private static final String SET_PASSWORD_PATH = "/api/auth/set-password";
    private static final String KNOWN_PASSWORD = "Sup3rSecret!";
    private static final String CHANGELOG = "database_files/changelog.xml";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            // stringtype=unspecified lets PostgreSQL implicitly cast the JSON converter's text value
            // into the jsonb display_preferences column (the handling the deployed app relies on).
            .withUrlParam("stringtype", "unspecified");

    private static boolean migrated = false;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        applyMigrationsOnce();

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    private static synchronized void applyMigrationsOnce() throws Exception {
        if (migrated) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
                liquibase.update(new Contexts());
            }
        }
        migrated = true;
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserDao userDao;

    @Autowired
    private RoleDao roleDao;

    @Autowired
    private InviteTokenDao inviteTokenDao;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    /**
     * The only mocked collaborator: replaces the real verifier so no Google network call is made
     * and the verified email is controllable per test.
     */
    @MockitoBean
    private GoogleIdTokenVerifier googleIdTokenVerifier;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Per-run id so seeded emails never collide across repeated runs (repeatability). */
    private String runId;

    @BeforeEach
    void setUp() {
        runId = UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("ACTIVE account -> 200 AUTHENTICATED (tokens present, setPasswordToken null)")
    void activeAccountReturnsAuthenticatedWithTokens() throws Exception {
        String email = seedUser(UserStatus.ACTIVE, true);
        stubVerifiedEmail(email);

        mockMvc.perform(post(GOOGLE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(googleJson("any-google-id-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHENTICATED"))
                .andExpect(jsonPath("$.tokens.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokens.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokens.expiresIn").isNumber())
                .andExpect(jsonPath("$.setPasswordToken").doesNotExist());
    }

    @Test
    @DisplayName("INVITED account -> 200 ACTIVATION_REQUIRED; minted token activates via set-password")
    void invitedAccountReturnsActivationRequiredAndTokenActivates() throws Exception {
        String email = seedUser(UserStatus.INVITED, false);
        Long userId = userDao.findByEmail(email).orElseThrow().getId();
        stubVerifiedEmail(email);

        // Google exchange for an INVITED account: no session, a fresh set-password token instead.
        String body = mockMvc.perform(post(GOOGLE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(googleJson("any-google-id-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATION_REQUIRED"))
                .andExpect(jsonPath("$.tokens").doesNotExist())
                .andExpect(jsonPath("$.setPasswordToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String setPasswordToken = objectMapper.readTree(body).path("setPasswordToken").asText();
        assertThat(setPasswordToken)
                .as("ACTIVATION_REQUIRED must carry a non-blank set-password token (14.5)")
                .isNotBlank();

        // The minted token is a genuine, unused invite token bound to the INVITED user.
        InviteTokenEntity minted = inviteTokenDao.findByToken(setPasswordToken).orElseThrow();
        assertThat(minted.isUsed()).as("minted set-password token must be unused").isFalse();
        assertThat(minted.getUser().getId()).isEqualTo(userId);

        // Follow-up POST /api/auth/set-password accepts the minted token -> 200 auto-login (14.5).
        mockMvc.perform(post(SET_PASSWORD_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setPasswordJson(setPasswordToken, KNOWN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());

        // Account is now ACTIVE and the minted token has been consumed.
        UserEntity activated = userDao.findById(userId).orElseThrow();
        assertThat(activated.getStatus())
                .as("account must be ACTIVE after set-password on the Google-minted token")
                .isEqualTo(UserStatus.ACTIVE);
        assertThat(inviteTokenDao.findByToken(setPasswordToken).orElseThrow().isUsed())
                .as("minted token must be marked used after set-password")
                .isTrue();
    }

    @Test
    @DisplayName("Unknown email -> 403 error.auth.google.no.account")
    void unknownEmailReturnsForbiddenNoAccount() throws Exception {
        // No user seeded for this email: the verified email is not linked to any account.
        stubVerifiedEmail("google-unknown+" + runId + "@example.com");

        mockMvc.perform(post(GOOGLE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(googleJson("any-google-id-token")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message")
                        .value("Skontaktuj się z administratorem, aby uzyskać konto."));
    }

    @Test
    @DisplayName("DEACTIVATED account -> 403 error.auth.account.deactivated")
    void deactivatedAccountReturnsForbiddenDeactivated() throws Exception {
        String email = seedUser(UserStatus.DEACTIVATED, true);
        stubVerifiedEmail(email);

        mockMvc.perform(post(GOOGLE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(googleJson("any-google-id-token")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message")
                        .value("Konto zostało dezaktywowane. Skontaktuj się z administratorem."));
    }

    @Test
    @DisplayName("Invalid token (verifier returns null) -> 401 error.auth.google.token.invalid; reaches controller (public)")
    void invalidTokenReturnsUnauthorizedTokenInvalid() throws Exception {
        // verify(...) returning null models an unverifiable token (bad signature/audience/expiry).
        try {
            when(googleIdTokenVerifier.verify(anyString())).thenReturn(null);
        } catch (Exception e) {
            throw new IllegalStateException("stubbing GoogleIdTokenVerifier.verify failed", e);
        }

        mockMvc.perform(post(GOOGLE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(googleJson("invalid-google-id-token")))
                // A business 401 with an ErrorResponse body proves the request reached the
                // controller through the /api/auth/** permitAll rule (not a bare security 401).
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message")
                        .value("Nieprawidłowy token Google. Spróbuj zalogować się ponownie."));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Stubs the verifier to return a token whose payload reports {@code email} as the verified
     * address, so the exchange branch is driven purely by the looked-up account's status.
     */
    private void stubVerifiedEmail(String email) {
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setEmail(email);
        GoogleIdToken token = Mockito.mock(GoogleIdToken.class);
        when(token.getPayload()).thenReturn(payload);
        try {
            when(googleIdTokenVerifier.verify(anyString())).thenReturn(token);
        } catch (Exception e) {
            throw new IllegalStateException("stubbing GoogleIdTokenVerifier.verify failed", e);
        }
    }

    /**
     * Seeds a user with the given status and a unique email using the seeded WORKER role. An ACTIVE
     * or DEACTIVATED user gets a bcrypt password hash; an INVITED user has none (matching the real
     * invite lifecycle where the password is set only on activation).
     *
     * @return the seeded user's email
     */
    private String seedUser(UserStatus statusValue, boolean withPassword) {
        RoleEntity workerRole = roleDao.findByCode("WORKER")
                .orElseThrow(() -> new IllegalStateException("WORKER role must be seeded by Liquibase"));

        String email = "google-" + statusValue.name().toLowerCase() + "+" + runId + "@example.com";

        UserEntity user = new UserEntity();
        user.setName("Google " + statusValue.name() + " User");
        user.setEmail(email);
        user.setRole(workerRole);
        user.setStatus(statusValue);
        user.setActive(statusValue == UserStatus.ACTIVE);
        user.setLocale("ru");
        if (withPassword) {
            user.setPasswordHash(passwordEncoder.encode(KNOWN_PASSWORD));
        }
        userDao.save(user);

        if (statusValue == UserStatus.INVITED) {
            // Mirror the real invite lifecycle: an INVITED account has an outstanding invite token.
            UserEntity persisted = userDao.findByEmail(email).orElseThrow();
            InviteTokenEntity invite = new InviteTokenEntity();
            invite.setToken(UUID.randomUUID().toString());
            invite.setUser(persisted);
            invite.setExpiresAt(java.time.Instant.now().plusSeconds(3600));
            invite.setUsed(false);
            inviteTokenDao.save(invite);
        }

        return email;
    }

    private static String googleJson(String idToken) {
        return "{\"idToken\": \"" + idToken + "\"}";
    }

    private static String setPasswordJson(String token, String password) {
        return "{\"token\": \"" + token + "\", \"password\": \"" + password + "\"}";
    }
}
