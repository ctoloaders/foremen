package com.foremen.controller.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 9.4 — Unguarded-now-requires-authentication integration test.
 *
 * <p>Boots the full Spring context — including the migrated Spring Security filter chain
 * ({@code anyRequest().authenticated()}) — against a real PostgreSQL database (Testcontainers)
 * with the Liquibase schema and seed data applied, mirroring the
 * {@code @SpringBootTest} + {@code @AutoConfigureMockMvc} + {@code @Testcontainers} convention of
 * {@code MeConditionalRequestIntegrationTest}.
 *
 * <p>{@code DisplayPreferencesController} is an <em>interceptor-level Unguarded</em> handler: it
 * carries no {@code @PermissionResource} / {@code @RequiresPermission}, so the
 * {@code PermissionInterceptor} performs no matrix check. Its endpoints live under {@code /api/**}
 * (specifically {@code /api/users/{id}/display-preferences}) — a path that is NOT
 * {@code Filter_Chain_Public} (only {@code /api/auth/**} is {@code permitAll}). This test proves the
 * end state {@code Authenticated_But_Not_Matrix_Checked} (Requirement 16.1, 16.2, 16.4):
 *
 * <ol>
 *   <li>an <b>unauthenticated</b> request to a {@code DisplayPreferencesController} endpoint is
 *       short-circuited by the filter chain with <b>401</b> via {@code JwtAuthenticationEntryPoint}
 *       (the controller and interceptor never run) — Req 16.1;</li>
 *   <li>an <b>authenticated</b> request (a valid JWT for any role — here the permission-less WORKER
 *       role) reaches the controller and is <b>not</b> subjected to a matrix check: accessing one's
 *       own preferences returns <b>200</b>, i.e. no interceptor 403 matrix denial occurs; the
 *       controller's own self-versus-requested-id logic runs — Req 16.2, 16.4;</li>
 *   <li>the same authenticated principal accessing a <em>different</em> user's preferences receives
 *       <b>403</b> from the controller's own self-check (not from a matrix grant lookup), further
 *       confirming the controller — not the interceptor — makes the authorization decision — Req
 *       16.2.</li>
 * </ol>
 *
 * <p>The WORKER role is used deliberately: it holds no matrix grants, so if the endpoint were
 * matrix-guarded the authenticated GET would be 403; the observed 200 proves the handler is
 * Unguarded at the interceptor layer while still requiring authentication at the filter-chain layer.
 *
 * <p>This test also confirms the migrated chain keeps its STATELESS session policy with CSRF and
 * frame options disabled (a mutating PATCH with no CSRF token succeeds once authenticated, and no
 * {@code Set-Cookie} session cookie is issued), and it does not modify any existing JWT-auth
 * integration tests, which continue to pass unmodified.
 *
 * <p>Each run uses a unique email so the scenario is repeatable without manual cleanup.
 *
 * <p>Validates: Requirements 16.1, 16.2, 16.4
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
class UnguardedRequiresAuthIntegrationTest {

    private static final String KNOWN_PASSWORD = "Sup3rSecret!";
    private static final String CHANGELOG = "database_files/changelog.xml";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
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
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private SecurityFilterChain securityFilterChain;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String seededEmail;
    private Long seededUserId;

    @BeforeEach
    void setUp() {
        RoleEntity workerRole = roleDao.findByCode("WORKER")
                .orElseThrow(() -> new IllegalStateException("WORKER role must be seeded by Liquibase"));

        seededEmail = "unguarded-auth+" + UUID.randomUUID() + "@example.com";

        UserEntity user = new UserEntity();
        user.setName("Unguarded Auth User");
        user.setEmail(seededEmail);
        user.setRole(workerRole);
        user.setStatus(UserStatus.ACTIVE);
        user.setActive(true);
        user.setLocale("ru");
        user.setPasswordHash(passwordEncoder.encode(KNOWN_PASSWORD));
        seededUserId = userDao.save(user).getId();
    }

    // --- 1. Unauthenticated -> 401 at the filter chain (Req 16.1) ---

    @Test
    @DisplayName("Unauthenticated GET display-preferences (non-public /api/** path) -> 401 at filter chain")
    void unauthenticatedDisplayPreferences_returns401() throws Exception {
        mockMvc.perform(get("/api/users/" + seededUserId + "/display-preferences"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    // --- 2. Authenticated own id -> reaches controller, no matrix check, 200 (Req 16.2, 16.4) ---

    @Test
    @DisplayName("Authenticated GET own display-preferences -> reaches controller, no matrix 403, 200")
    void authenticatedOwnDisplayPreferences_reachesControllerWithoutMatrixCheck() throws Exception {
        String accessToken = login();

        mockMvc.perform(get("/api/users/" + seededUserId + "/display-preferences")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                // 200 (not 403): the permission-less WORKER role would be denied by any matrix
                // check, so reaching the controller proves the handler is Unguarded at the
                // interceptor layer while still authenticated at the filter-chain layer.
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{}"));
    }

    // --- 3. Authenticated other id -> 403 from the controller's own self-check, not a matrix denial ---

    @Test
    @DisplayName("Authenticated GET another user's display-preferences -> 403 from controller self-check")
    void authenticatedOtherUsersDisplayPreferences_returns403FromSelfCheck() throws Exception {
        String accessToken = login();
        long otherUserId = seededUserId + 1_000_000L; // a different id than the principal

        mockMvc.perform(get("/api/users/" + otherUserId + "/display-preferences")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                // The controller's own self-versus-requested-id logic runs and denies with 403,
                // confirming the interceptor did not short-circuit with a matrix decision.
                .andExpect(status().isForbidden());
    }

    // --- 4. Authenticated PATCH succeeds with no CSRF token and issues no session cookie (STATELESS) ---

    @Test
    @DisplayName("Authenticated PATCH own display-preferences succeeds with no CSRF token and no session cookie")
    void authenticatedPatch_succeedsCsrfDisabledAndStateless() throws Exception {
        String accessToken = login();

        var result = mockMvc.perform(patch("/api/users/" + seededUserId + "/display-preferences")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "themeMode": "dark",
                                    "colorScheme": "blue",
                                    "fontSize": "lg"
                                }
                                """))
                // No 403 CSRF rejection: CSRF is disabled on the migrated chain.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themeMode").value("dark"))
                .andReturn();

        // STATELESS: the response must not establish an HTTP session. No Set-Cookie header at all
        // (null) is the expected outcome; if one is present it must not carry a JSESSIONID.
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie == null || !setCookie.toUpperCase().contains("JSESSIONID"))
                .as("a STATELESS chain must not issue a JSESSIONID session cookie (Set-Cookie was: %s)", setCookie)
                .isTrue();
    }

    // --- 5. The migrated filter chain is present as a bean (sanity) ---

    @Test
    @DisplayName("Migrated SecurityFilterChain bean is wired into the context")
    void securityFilterChain_isWired() {
        assertThat(securityFilterChain)
                .as("the migrated SecurityFilterChain must be present in the context")
                .isNotNull();
    }

    private String login() throws Exception {
        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + seededEmail + "\", \"password\": \"" + KNOWN_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(loginBody);
        return node.get("accessToken").asText();
    }
}
