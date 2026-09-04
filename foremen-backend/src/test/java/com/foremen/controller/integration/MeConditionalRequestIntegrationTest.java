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
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Conditional-request integration test for {@code GET /api/auth/me} (task 17.3) running the full
 * Spring context — including the Spring Security filter chain — against a real PostgreSQL database
 * (Testcontainers) with the Liquibase schema and seed data applied.
 *
 * <p>The test drives the real endpoint through the whole conditional-request lifecycle with a real
 * access token obtained by logging in against the seeded user:
 * <ol>
 *   <li>{@code GET /api/auth/me} with a valid access token &rarr; 200 with a strong, quoted
 *       {@code ETag} and {@code Cache-Control: no-cache, private} (Requirements 13.1, 13.4),</li>
 *   <li>{@code GET /api/auth/me} with {@code If-None-Match: <etag>} &rarr; 304 Not Modified with an
 *       empty body, echoing the same ETag (Requirements 13.2, 13.3),</li>
 *   <li>after the user's role (and therefore its role code and permissions) changes, the same
 *       {@code If-None-Match} no longer matches: the endpoint returns 200 with a <em>different</em>
 *       ETag (Requirements 13.1, 13.2).</li>
 * </ol>
 *
 * <p>The full Spring Security chain is wired via {@link AutoConfigureMockMvc} with an
 * {@code @Autowired MockMvc}, so the {@code JwtAuthenticationFilter} validates the {@code Bearer}
 * token and establishes the {@code @AuthenticationPrincipal} the controller depends on.
 *
 * <p>The database schema and the built-in roles/permissions are provisioned by applying the full
 * Liquibase changelog against the Testcontainers PostgreSQL instance before the Spring context
 * starts (Spring Boot 4 does not auto-run Liquibase without the dedicated autoconfiguration module,
 * so the changelog is applied explicitly here — the same pattern used by {@code
 * AuthFlowIntegrationTest}). An ACTIVE user with a known bcrypt-hashed password and the seeded
 * WORKER role (which has no permissions) is then seeded through {@link UserDao}. The role change
 * switches the user to the seeded ADMIN role (a different role code with permissions), guaranteeing
 * a different canonical representation and therefore a different ETag. Each run uses a unique email
 * so the test is repeatable without manual cleanup.
 *
 * <p>Validates: Requirements 13.1, 13.2, 13.3, 13.4
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.liquibase.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
class MeConditionalRequestIntegrationTest {

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
        // Ensure the container is up and the schema (plus seeded roles/permissions) is applied
        // before the Spring context — and its JPA repositories — start querying the database.
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String seededEmail;
    private Long seededUserId;

    @BeforeEach
    void setUp() {
        RoleEntity workerRole = roleDao.findByCode("WORKER")
                .orElseThrow(() -> new IllegalStateException("WORKER role must be seeded by Liquibase"));

        seededEmail = "me-etag+" + UUID.randomUUID() + "@example.com";

        UserEntity user = new UserEntity();
        user.setName("Me ETag User");
        user.setEmail(seededEmail);
        user.setRole(workerRole);
        user.setStatus(UserStatus.ACTIVE);
        user.setActive(true);
        user.setLocale("ru");
        user.setPasswordHash(passwordEncoder.encode(KNOWN_PASSWORD));
        seededUserId = userDao.save(user).getId();
    }

    @Test
    @DisplayName("GET /me -> 200 strong ETag; If-None-Match -> 304 empty; after role change -> 200 new ETag")
    void conditionalMeReturns304ThenChangesOnRoleChange() throws Exception {
        // 0. Login to obtain a real access token that carries the seeded user's identity/role.
        String accessToken = login();

        // 1. First GET /me -> 200 with a strong (quoted) ETag and no-cache/private caching (13.1, 13.4).
        String firstEtag = mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, Matchers.containsString("no-cache")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, Matchers.containsString("private")))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.id").value(seededUserId.intValue()))
                .andExpect(jsonPath("$.email").value(seededEmail))
                .andExpect(jsonPath("$.roleCode").value("WORKER"))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        assertThat(firstEtag)
                .as("the /me ETag must be a strong, quoted validator (13.1)")
                .isNotBlank()
                .startsWith("\"")
                .endsWith("\"");

        // 2. Follow-up GET /me with If-None-Match: <etag> -> 304 Not Modified with an empty body (13.2, 13.3).
        String notModifiedBody = mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .header(HttpHeaders.IF_NONE_MATCH, firstEtag))
                .andExpect(status().isNotModified())
                .andExpect(header().string(HttpHeaders.ETAG, firstEtag))
                .andReturn().getResponse().getContentAsString();

        assertThat(notModifiedBody)
                .as("a 304 Not Modified must carry an empty body (13.3)")
                .isEmpty();

        // 3. Change the user's role/permissions (WORKER, no permissions -> ADMIN, with permissions).
        changeRoleToAdmin();

        // 4. The same If-None-Match no longer matches: 200 with a DIFFERENT ETag (13.1, 13.2).
        String secondEtag = mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .header(HttpHeaders.IF_NONE_MATCH, firstEtag))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.roleCode").value("ADMIN"))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        assertThat(secondEtag)
                .as("the ETag must change after a role/permission change (13.1, 13.2)")
                .isNotBlank()
                .isNotEqualTo(firstEtag);
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

    private void changeRoleToAdmin() {
        RoleEntity adminRole = roleDao.findByCode("ADMIN")
                .orElseThrow(() -> new IllegalStateException("ADMIN role must be seeded by Liquibase"));
        UserEntity user = userDao.findById(seededUserId)
                .orElseThrow(() -> new IllegalStateException("seeded user must exist"));
        user.setRole(adminRole);
        userDao.save(user);
    }
}
