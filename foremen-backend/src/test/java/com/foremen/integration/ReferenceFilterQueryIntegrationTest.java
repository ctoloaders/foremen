package com.foremen.integration;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * End-to-end reference-filter query integration tests for FOR-04-01 (task 3.3).
 *
 * <p>The reference filter emits the existing query grammar against the target id path
 * ({@code role.id}) so a table can be filtered by a related entity with no new query engine
 * (design §4). These tests prove the full {@code GET /api/users} request path honours those emitted
 * fragments against a real Spring MVC + Spring Security stack and a Testcontainers PostgreSQL
 * instance, mirroring the {@code @SpringBootTest(MOCK)} + {@code @AutoConfigureMockMvc} +
 * {@code @Testcontainers} + {@code @ActiveProfiles("integration-test")} harness of
 * {@link EndToEndResolutionIntegrationTest} and {@link ReferenceOptionsEndpointIntegrationTest}.
 * Under the {@code integration-test} profile Liquibase is disabled and Hibernate {@code create-drop}
 * builds the schema, so every row (roles, users) is created programmatically via the
 * {@link EntityManager} and removed in {@link #cleanUp()} so the suite re-runs without manual DB
 * cleanup.
 *
 * <p>Reads use an ADMIN token: {@code ForemenPermissionEvaluator} bypasses the ABAC matrix for the
 * exact {@code ADMIN} role code, so {@code GET /api/users} returns 200 without seeding a USERS/READ
 * grant — isolating these assertions to the query-filter behaviour rather than authorization
 * (authorization is covered by {@link EndToEndResolutionIntegrationTest} and
 * {@link ReferenceOptionsEndpointIntegrationTest}).
 *
 * <p>Query grammar note: the implemented grammar uses tilde-wrapped operators and the {@code AND}
 * keyword as the conjunction (design operator-symbol correction; {@link com.foremen.service.query.QueryOperator},
 * {@link com.foremen.service.query.QueryTokenizer}). The reference-filter fragments under test are
 * therefore:
 * <ul>
 *   <li>single select: {@code role.id==<id>} (Req 3.4);</li>
 *   <li>multi select: {@code role.id~in~<id1>,<id2>} — comma-joined, no parentheses (Req 4.2);</li>
 *   <li>composition: {@code role.id==<id> AND name~ct~<term>} joined by the {@code AND} keyword the
 *       tokenizer recognises (Req 5.2).</li>
 * </ul>
 *
 * <p>Assertions:
 * <ul>
 *   <li>{@code role.id==<id>} returns only users whose role is that id (Req 3.4);</li>
 *   <li>{@code role.id~in~<id1>,<id2>} returns users with either role (Req 4.2);</li>
 *   <li>{@code role.id==<id> AND name~ct~<term>} returns the intersection — same-role users whose
 *       name matches the second filter, composed with AND (Req 5.2).</li>
 * </ul>
 *
 * <p>Repeatability: every seeded role code and user email carries a unique per-run id, and every
 * row this run created is deleted in {@link #cleanUp()} (users first, then roles, FK order), so the
 * suite re-runs without manual cleanup and never collides with earlier runs or seed rows.
 *
 * <p>Validates: Requirements 3.4, 4.2, 5.2
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
class ReferenceFilterQueryIntegrationTest {

    private static final String USERS_PATH = "/api/users";

    private static final AtomicLong COUNTER = new AtomicLong();

    private static final JsonMapper JSON = JsonMapper.builder().build();

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
        // The integration-test profile disables Open-Session-In-View, but production leaves it at
        // the Spring Boot default (true). GET /api/users maps each row's lazy @ManyToOne role name
        // (UserServiceMapper reads role.getNameRU()) during DTO conversion, which requires an open
        // session — as it has in production via OSIV. Restore the production default here so this
        // e2e read reflects the real endpoint rather than the profile's OSIV-off tuning.
        registry.add("spring.jpa.open-in-view", () -> "true");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    /** Unique per-run id so seeded role codes and user emails never collide across runs / seed rows. */
    private final String runId = UUID.randomUUID().toString().substring(0, 6).toUpperCase();

    @AfterEach
    void cleanUp() {
        // Bulk deletes need an active transaction; @AfterEach is not test-transaction-managed.
        // Delete this run's users first (they FK the roles), then this run's roles.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery("delete from UserEntity u where u.email like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from RoleEntity r where r.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
        });
    }

    // ------------------------------------------------------------------
    // Single select: role.id==<id> (Req 3.4)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("role.id==<id> returns only users with that role")
    void singleEquality_returnsOnlyUsersWithThatRole() throws Exception {
        RoleEntity foreman = persistRole("FOREMAN");
        RoleEntity manager = persistRole("MANAGER");

        UserEntity alice = persistUser("Alice", foreman);
        UserEntity bob = persistUser("Bob", foreman);
        persistUser("Carol", manager); // different role — must be excluded

        MvcResult result = mockMvc.perform(get(USERS_PATH)
                        .header("Authorization", adminBearer())
                        .param("query", "role.id==" + foreman.getId())
                        .param("page", "0")
                        .param("size", "50"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        List<Long> ids = idsOf(result);
        assertThat(ids)
                .as("role.id==<foreman> must return exactly the two FOREMAN users and no MANAGER user")
                .containsExactlyInAnyOrder(alice.getId(), bob.getId());
    }

    // ------------------------------------------------------------------
    // Multi select: role.id~in~<id1>,<id2> (Req 4.2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("role.id~in~<id1>,<id2> returns users with either role")
    void inSet_returnsUsersWithEitherRole() throws Exception {
        RoleEntity foreman = persistRole("FOREMAN");
        RoleEntity manager = persistRole("MANAGER");
        RoleEntity worker = persistRole("WORKER");

        UserEntity alice = persistUser("Alice", foreman);
        UserEntity carol = persistUser("Carol", manager);
        persistUser("Dave", worker); // third role — must be excluded

        MvcResult result = mockMvc.perform(get(USERS_PATH)
                        .header("Authorization", adminBearer())
                        .param("query", "role.id~in~" + foreman.getId() + "," + manager.getId())
                        .param("page", "0")
                        .param("size", "50"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        List<Long> ids = idsOf(result);
        assertThat(ids)
                .as("role.id~in~<foreman>,<manager> must return users of either role, excluding WORKER")
                .containsExactlyInAnyOrder(alice.getId(), carol.getId());
    }

    // ------------------------------------------------------------------
    // Composition with a second filter via AND (Req 5.2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("role.id==<id> AND name~ct~<term> composes with AND (intersection)")
    void referenceFilterComposesWithSecondFilterViaAnd() throws Exception {
        RoleEntity foreman = persistRole("FOREMAN");
        RoleEntity manager = persistRole("MANAGER");

        // Two FOREMAN users; only one matches the name filter.
        UserEntity anna = persistUser("Anna", foreman);   // FOREMAN + name matches "ann"
        persistUser("Boris", foreman);                     // FOREMAN but name does not match "ann"
        // A MANAGER user whose name would match the name filter — must be excluded by the role filter.
        persistUser("Annette", manager);

        MvcResult result = mockMvc.perform(get(USERS_PATH)
                        .header("Authorization", adminBearer())
                        .param("query", "role.id==" + foreman.getId() + " AND name~ct~Ann")
                        .param("page", "0")
                        .param("size", "50"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        List<Long> ids = idsOf(result);
        assertThat(ids)
                .as("the reference filter AND the name filter must return only the FOREMAN user "
                        + "whose name matches (the intersection), excluding the non-matching FOREMAN "
                        + "and the matching MANAGER")
                .containsExactly(anna.getId());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Extracts the {@code id} of each user row in the returned page. */
    private List<Long> idsOf(MvcResult result) throws Exception {
        JsonNode content = JSON.readTree(result.getResponse().getContentAsString()).get("content");
        List<Long> ids = new ArrayList<>();
        content.forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }

    /** ADMIN bearer token — the evaluator bypasses the matrix for the exact ADMIN role code. */
    private String adminBearer() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(
                nextId(), "ADMIN", "admin+" + runId + "@example.com");
    }

    /**
     * Persists (committed) a role with a unique per-run code. The {@code namePrefix} makes the code
     * readable ({@code FOREMAN_<runId>_<n>}); the localized names are required (NOT NULL).
     */
    private RoleEntity persistRole(String namePrefix) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            RoleEntity role = new RoleEntity();
            role.setCode(namePrefix + "_" + runId + "_" + COUNTER.incrementAndGet());
            role.setNameRU(namePrefix + " RU " + runId);
            role.setNamePL(namePrefix + " PL " + runId);
            role.setSystem(false);
            entityManager.persist(role);
            entityManager.flush();
            return role;
        });
    }

    /**
     * Persists (committed) a user referencing the given role. Name is the supplied display name;
     * the email carries the per-run id so cleanup and isolation are deterministic. The user keeps
     * the entity defaults for status (INVITED), locale ("ru") and active (true), and requires a
     * non-null role (FK {@code role_id}).
     */
    private UserEntity persistUser(String name, RoleEntity role) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            RoleEntity attached = entityManager.find(RoleEntity.class, role.getId());
            UserEntity user = new UserEntity();
            user.setName(name);
            user.setEmail(name.toLowerCase() + "+" + runId + "-" + COUNTER.incrementAndGet()
                    + "@example.com");
            user.setRole(attached);
            user.setActive(true);
            user.setStatus(UserStatus.INVITED);
            user.setLocale("ru");
            entityManager.persist(user);
            entityManager.flush();
            return user;
        });
    }

    private long nextId() {
        return 960_000L + COUNTER.incrementAndGet();
    }
}
