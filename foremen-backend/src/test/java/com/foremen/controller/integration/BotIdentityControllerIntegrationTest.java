package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.RoleDao;
import com.foremen.dao.model.RoleEntity;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Integration tests for the loaders-bot identity-token endpoint (FOR-08, Requirement 1 /
 * verification 8.1), exercising the real Spring Security filter chain against a Testcontainers
 * PostgreSQL instance following the project's established {@code @SpringBootTest} +
 * {@code @Testcontainers} + {@code @DynamicPropertySource} pattern (see
 * {@code SecurityConfigIntegrationTest}).
 *
 * <p>Covers:
 * <ul>
 *   <li><b>401 for an unauthenticated caller</b> — {@code POST /api/bot/identity-token} without a
 *       Bearer token is rejected by {@code JwtAuthenticationEntryPoint} (Requirement 1.4), and with
 *       an invalid token (Requirement 1.4).</li>
 *   <li><b>Valid token for an authenticated caller</b> — a well-formed Bearer token yields HTTP 200
 *       with a signed identity token whose {@code sub} is the caller's id, whose {@code traits}
 *       carries {@code name}/{@code locale} (locale from {@code Accept-Language}), and whose
 *       {@code roles} carries the Company_Role code, with {@code exp} ≤ 24 h (Requirement 1.1,
 *       1.2). The returned token is parsed and verified with the configured test identity secret.
 *       </li>
 * </ul>
 *
 * <p>The identity secret used here is the one seeded in {@code application-integration-test.yml}
 * ({@code foremen.bot.identity-secret}); the test mirrors it as a constant so the returned token
 * can be verified end-to-end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
@Transactional
class BotIdentityControllerIntegrationTest {

    private static final String PATH = "/api/bot/identity-token";
    private static final String USER_ROLE = "ESTIMATOR";
    private static final String USER_NAME = "Jan Kowalski";

    /** Must match {@code foremen.bot.identity-secret} in application-integration-test.yml. */
    private static final String IDENTITY_SECRET = "test-only-bot-identity-secret-0123456789-abcdef";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private RoleDao roleDao;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PersistenceContext
    private EntityManager entityManager;

    private Long activeUserId;
    private String activeUserEmail;

    @BeforeEach
    void seedActiveUser() {
        RoleEntity role = roleDao.findByCode(USER_ROLE).orElseGet(() -> {
            RoleEntity r = new RoleEntity();
            r.setCode(USER_ROLE);
            r.setNameRU("Сметчик");
            r.setNamePL("Kosztorysant");
            r.setSystem(true);
            return roleDao.save(r);
        });
        entityManager.flush();

        activeUserEmail = "bot-identity+" + UUID.randomUUID() + "@example.com";

        entityManager.createNativeQuery("""
                        INSERT INTO users (name, email, role_id, active, status, locale,
                                           display_preferences, created_date)
                        VALUES (:name, :email, :roleId, true, 'ACTIVE', 'ru',
                                CAST(NULL AS jsonb), NOW())
                        """)
                .setParameter("name", USER_NAME)
                .setParameter("email", activeUserEmail)
                .setParameter("roleId", role.getId())
                .executeUpdate();
        entityManager.flush();

        activeUserId = ((Number) entityManager
                .createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", activeUserEmail)
                .getSingleResult()).longValue();
    }

    private static Claims parseIdentityToken(String token) {
        SecretKey key = Keys.hmacShaKeyFor(IDENTITY_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    // ---------------------------------------------------------------------
    // 401 — unauthenticated caller (Requirement 1.4)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/bot/identity-token without a token → 401 (1.4)")
    void identityTokenWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("POST /api/bot/identity-token with an invalid Bearer token → 401 (1.4)")
    void identityTokenWithInvalidTokenReturns401() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    // ---------------------------------------------------------------------
    // Valid token — authenticated caller (Requirement 1.1, 1.2)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/bot/identity-token with a valid token → 200 + correct sub/traits/roles (1.1, 1.2)")
    void identityTokenWithValidTokenReturnsSignedIdentityToken() throws Exception {
        String accessToken = jwtTokenProvider.generateAccessToken(activeUserId, USER_ROLE, activeUserEmail);

        MvcResult result = mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Accept-Language", "ru"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String identityToken = body.get("token").asText();
        assertThat(identityToken).isNotBlank();

        Claims claims = parseIdentityToken(identityToken);

        // sub = caller id (Requirement 1.1)
        assertThat(claims.getSubject()).isEqualTo(String.valueOf(activeUserId));

        // traits.name + traits.locale (ru from Accept-Language) (Requirement 1.2)
        @SuppressWarnings("unchecked")
        Map<String, Object> traits = claims.get("traits", Map.class);
        assertThat(traits).containsEntry("name", USER_NAME).containsEntry("locale", "ru");

        // roles = [Company_Role code] (Requirement 1.2)
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);
        assertThat(roles).containsExactly(USER_ROLE);

        // iat/exp present and exp ≤ 24 h (Requirement 1.1)
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
        assertThat(claims.getExpiration().toInstant())
                .isAfter(claims.getIssuedAt().toInstant())
                .isBeforeOrEqualTo(claims.getIssuedAt().toInstant().plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("POST /api/bot/identity-token resolves locale=pl from a non-ru Accept-Language (1.2)")
    void identityTokenResolvesPolishLocaleFromAcceptLanguage() throws Exception {
        String accessToken = jwtTokenProvider.generateAccessToken(activeUserId, USER_ROLE, activeUserEmail);

        MvcResult result = mockMvc.perform(post(PATH)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Accept-Language", "pl"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        Claims claims = parseIdentityToken(body.get("token").asText());

        @SuppressWarnings("unchecked")
        Map<String, Object> traits = claims.get("traits", Map.class);
        assertThat(traits).containsEntry("locale", "pl");
    }
}
