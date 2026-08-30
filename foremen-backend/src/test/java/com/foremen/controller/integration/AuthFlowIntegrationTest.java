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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end authentication flow integration test (task 15.2) running the full Spring context
 * against a real PostgreSQL database (Testcontainers) with the Liquibase schema applied.
 *
 * <p>The test drives the real {@code /api/auth} endpoints through the whole rotation lifecycle:
 * <ol>
 *   <li>login &rarr; capture the first access/refresh pair,</li>
 *   <li>refresh with the first refresh token &rarr; capture a rotated second pair and assert the
 *       new refresh token differs from the old one (rotation, Requirement 7.3),</li>
 *   <li>refresh again with the now-rotated first refresh token &rarr; 401 (one-time-use,
 *       Requirement 7.7),</li>
 *   <li>logout with the second refresh token &rarr; 204 (Requirement 8.2),</li>
 *   <li>refresh with the logged-out second refresh token &rarr; 401 (post-logout rejection,
 *       Requirement 8.4).</li>
 * </ol>
 *
 * <p>The database schema and the built-in roles are provisioned by applying the full Liquibase
 * changelog against the Testcontainers PostgreSQL instance before the Spring context starts (Spring
 * Boot 4 does not auto-run Liquibase without the dedicated autoconfiguration module, so the
 * changelog is applied explicitly here — the same pattern used by {@code AuthMigrationIntegrationTest}).
 * An ACTIVE user with a known bcrypt-hashed password is then seeded through {@link UserDao} using
 * the WORKER role from the Liquibase seed (Requirement 3.3). Each run uses a unique email so the
 * test is repeatable without manual cleanup.
 *
 * <p>Validates: Requirements 3.3, 7.3, 7.7, 8.2, 8.4
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false"
})
@ActiveProfiles("integration-test")
@Testcontainers
class AuthFlowIntegrationTest {

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
        // Ensure the container is up and the schema (plus seeded roles) is applied before the
        // Spring context — and its JPA repositories — start querying the database.
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
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserDao userDao;

    @Autowired
    private RoleDao roleDao;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;
    private String seededEmail;

    @BeforeEach
    @Transactional
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        RoleEntity workerRole = roleDao.findByCode("WORKER")
                .orElseThrow(() -> new IllegalStateException("WORKER role must be seeded by Liquibase"));

        seededEmail = "auth-e2e+" + UUID.randomUUID() + "@example.com";

        UserEntity user = new UserEntity();
        user.setName("E2E Flow User");
        user.setEmail(seededEmail);
        user.setRole(workerRole);
        user.setStatus(UserStatus.ACTIVE);
        user.setActive(true);
        user.setLocale("ru");
        user.setPasswordHash(passwordEncoder.encode(KNOWN_PASSWORD));
        userDao.save(user);
    }

    @Test
    @DisplayName("login -> refresh -> logout -> refresh-again: rotation and post-logout rejection")
    void fullAuthFlowRotatesAndRejectsAfterLogout() throws Exception {
        // 1. Login with the seeded credentials -> 200, capture the first token pair (3.3).
        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(seededEmail, KNOWN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String accessToken1 = readField(loginBody, "accessToken");
        String refreshToken1 = readField(loginBody, "refreshToken");
        assertThat(accessToken1).isNotBlank();
        assertThat(refreshToken1).isNotBlank();

        // 2. Refresh with refreshToken1 -> 200, capture the rotated pair; refreshToken2 differs (7.3).
        String refreshBody = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson(refreshToken1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String refreshToken2 = readField(refreshBody, "refreshToken");
        assertThat(refreshToken2)
                .as("refresh must rotate: the new refresh token differs from the supplied one (7.3)")
                .isNotBlank()
                .isNotEqualTo(refreshToken1);

        // 3. Refresh AGAIN with the now-rotated refreshToken1 -> 401 (revoked by rotation, 7.7).
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson(refreshToken1)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        // 4. Logout with refreshToken2 -> 204 (8.2).
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson(refreshToken2)))
                .andExpect(status().isNoContent());

        // 5. Refresh with the logged-out refreshToken2 -> 401 (post-logout rejection, 8.4).
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshJson(refreshToken2)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    private String readField(String jsonBody, String field) throws Exception {
        JsonNode node = objectMapper.readTree(jsonBody);
        return node.get(field).asText();
    }

    private static String loginJson(String email, String password) {
        return "{\"email\": \"" + email + "\", \"password\": \"" + password + "\"}";
    }

    private static String refreshJson(String refreshToken) {
        return "{\"refreshToken\": \"" + refreshToken + "\"}";
    }
}
