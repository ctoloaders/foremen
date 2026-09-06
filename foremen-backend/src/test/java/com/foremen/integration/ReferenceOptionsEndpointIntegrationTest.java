package com.foremen.integration;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.service.permission.PermissionCache;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Integration tests for the reference-filter options listing (FOR-04-01, task 3.2).
 *
 * <p>The reference dropdown does not use a dedicated endpoint — it reuses the target resource's
 * existing guarded list endpoint (design §2, task 3.1). These tests exercise that contract against
 * the {@code ROLES} target ({@code GET /api/roles}) over the real Spring MVC + Spring Security
 * stack and a Testcontainers PostgreSQL instance, mirroring the
 * {@code @SpringBootTest(MOCK)} + {@code @AutoConfigureMockMvc} + {@code @Testcontainers} +
 * {@code @ActiveProfiles("integration-test")} harness of {@link EndToEndResolutionIntegrationTest}.
 * Under the {@code integration-test} profile Liquibase is disabled and Hibernate {@code create-drop}
 * builds the schema, so every row (roles, resources, operations, grants) is created programmatically
 * via the {@link EntityManager} and removed in {@link #cleanUp()} so the suite re-runs without manual
 * DB cleanup.
 *
 * <p>Query grammar note: the implemented grammar uses tilde-wrapped operators (design
 * operator-symbol correction), so the options request is
 * {@code GET /api/roles?sort=name,asc&query=name~ct~<term>&page&size}
 * (contains is {@code ~ct~}, not the illustrative {@code =ct=}).
 *
 * <p>Assertions:
 * <ul>
 *   <li>options are ordered ascending by the locale-resolved name (Req 2.1, 2.3);</li>
 *   <li>{@code name~ct~} filters case-insensitively on the locale name preserving the order
 *       (Req 2.2);</li>
 *   <li>pagination reports whether more pages exist ({@code totalPages}/{@code last}) (Req 2.4);</li>
 *   <li>the endpoint is guarded by {@code ROLES}/{@code READ}: 401 without a token, 403 for a role
 *       without the READ grant, 200 for a role holding {@code ROLES}/{@code READ}, and 200 for
 *       ADMIN (Req 2.5).</li>
 * </ul>
 *
 * <p>Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
class ReferenceOptionsEndpointIntegrationTest {

    private static final String ROLES_PATH = "/api/roles";

    private static final AtomicLong COUNTER = new AtomicLong();

    private static final Set<String> SEEDED_RESOURCE_CODES = Set.of("ROLES");
    private static final Set<String> SEEDED_OPERATION_CODES = Set.of("READ", "CREATE");

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
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private PermissionCache permissionCache;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    /** Unique per-run id so option-role names/codes never collide with earlier runs or seed rows. */
    private final String runId = UUID.randomUUID().toString().substring(0, 6).toUpperCase();

    @AfterEach
    void cleanUp() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery(
                            "delete from RoleResourceEntity rr where rr.role.id in "
                                    + "(select r.id from RoleEntity r where r.code like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from RoleEntity r where r.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from ResourceEntity res where res.code in :codes")
                    .setParameter("codes", SEEDED_RESOURCE_CODES)
                    .executeUpdate();
            entityManager.createQuery("delete from OperationEntity op where op.code in :codes")
                    .setParameter("codes", SEEDED_OPERATION_CODES)
                    .executeUpdate();
        });
    }

    // ------------------------------------------------------------------
    // Ordering (Req 2.1, 2.3) + case-insensitive contains search (Req 2.2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Options are ordered ascending by the locale-resolved name (PL default)")
    void options_orderedAscendingByLocaleName() throws Exception {
        // Seed three option rows whose PL names sort differently from their insertion order.
        // Each PL name is prefixed with the run id so the search below isolates this run's rows.
        String p = runId + "-";
        persistOptionRole(p + "Ceramika", p + "-ins3");   // C
        persistOptionRole(p + "Aluminium", p + "-ins1");  // A
        persistOptionRole(p + "Beton", p + "-ins2");      // B

        RoleEntity reader = persistRoleWithGrants(grant("ROLES", "READ"));

        MvcResult result = mockMvc.perform(get(ROLES_PATH)
                        .header("Authorization", bearer(reader))
                        .param("sort", "name,asc")
                        .param("query", "name~ct~" + p)
                        .param("page", "0")
                        .param("size", "50"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        List<String> names = localizedNames(result);
        // Exactly this run's three rows, alphabetical by PL name.
        assertThat(names)
                .as("options must be returned ascending by the locale-resolved (PL) name")
                .containsExactly(p + "Aluminium", p + "Beton", p + "Ceramika");
    }

    @Test
    @DisplayName("name~ct~ filters case-insensitively on the locale name, preserving ascending order")
    void options_containsFilterIsCaseInsensitive() throws Exception {
        String p = runId + "-";
        // Two matches for the term "kabina" (mixed case) and one non-match.
        persistOptionRole(p + "Kabina prysznicowa", p + "k2"); // matches "kabina"
        persistOptionRole(p + "KABINA sauna", p + "k1");        // matches "kabina" (upper)
        persistOptionRole(p + "Okno", p + "k3");                // does not match

        RoleEntity reader = persistRoleWithGrants(grant("ROLES", "READ"));

        // Lower-case search term must still match the upper/mixed-case names.
        MvcResult result = mockMvc.perform(get(ROLES_PATH)
                        .header("Authorization", bearer(reader))
                        .param("sort", "name,asc")
                        .param("query", "name~ct~" + p + "kabina")
                        .param("page", "0")
                        .param("size", "50"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        List<String> names = localizedNames(result);
        assertThat(names)
                .as("case-insensitive contains must match both casings and keep ascending order")
                .containsExactly(p + "KABINA sauna", p + "Kabina prysznicowa");
    }

    @Test
    @DisplayName("Accept-Language: ru orders and filters by the RU name")
    void options_localeResolvedForRussian() throws Exception {
        String p = runId + "-";
        // RU / PL names deliberately sort in opposite orders so the assertion proves RU resolution.
        persistOptionRole("PL-" + p + "Zeta", "RU-" + p + "Alfa"); // RU: Alfa, PL: Zeta
        persistOptionRole("PL-" + p + "Alfa", "RU-" + p + "Zeta"); // RU: Zeta, PL: Alfa

        RoleEntity reader = persistRoleWithGrants(grant("ROLES", "READ"));

        MvcResult result = mockMvc.perform(get(ROLES_PATH)
                        .header("Authorization", bearer(reader))
                        .header("Accept-Language", "ru")
                        .param("sort", "name,asc")
                        .param("query", "name~ct~RU-" + p)
                        .param("page", "0")
                        .param("size", "50"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        List<String> names = localizedNames(result);
        assertThat(names)
                .as("with Accept-Language: ru the localized name and ordering must resolve to RU")
                .containsExactly("RU-" + p + "Alfa", "RU-" + p + "Zeta");
    }

    // ------------------------------------------------------------------
    // Pagination reports more pages (Req 2.4)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Pagination reports more pages exist (totalPages > 1, last=false) for a small page size")
    void options_paginationReportsMorePages() throws Exception {
        String p = runId + "-";
        // Five option rows; requesting size=2 must report multiple pages and not the last page.
        for (int i = 0; i < 5; i++) {
            persistOptionRole(p + "Opt-" + i, p + "opt-" + i);
        }

        RoleEntity reader = persistRoleWithGrants(grant("ROLES", "READ"));

        MvcResult firstPage = mockMvc.perform(get(ROLES_PATH)
                        .header("Authorization", bearer(reader))
                        .param("sort", "name,asc")
                        .param("query", "name~ct~" + p + "Opt-")
                        .param("page", "0")
                        .param("size", "2"))
                .andReturn();

        assertThat(firstPage.getResponse().getStatus()).isEqualTo(200);

        JsonNode page = JSON.readTree(firstPage.getResponse().getContentAsString());
        assertThat(page.get("content").size())
                .as("first page must contain exactly the requested page size")
                .isEqualTo(2);
        assertThat(page.get("totalElements").asLong())
                .as("all five seeded options must be counted")
                .isEqualTo(5);
        assertThat(page.get("totalPages").asInt())
                .as("five elements at size 2 must span more than one page")
                .isGreaterThan(1);
        assertThat(page.get("last").asBoolean())
                .as("the first of several pages must not be flagged as the last page")
                .isFalse();
    }

    // ------------------------------------------------------------------
    // READ guard: 401 / 403 / 200 (Req 2.5)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("No token -> 401 (options endpoint is authenticated)")
    void options_noToken_returns401() throws Exception {
        MvcResult result = mockMvc.perform(get(ROLES_PATH)
                        .param("sort", "name,asc")
                        .param("page", "0")
                        .param("size", "10"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("an unauthenticated options request must yield 401")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("Token whose role lacks ROLES/READ -> 403")
    void options_withoutReadGrant_returns403() throws Exception {
        // Holds ROLES/CREATE only -> lacks the ROLES/READ pair the list endpoint resolves to.
        RoleEntity role = persistRoleWithGrants(grant("ROLES", "CREATE"));

        MvcResult result = mockMvc.perform(get(ROLES_PATH)
                        .header("Authorization", bearer(role))
                        .param("sort", "name,asc")
                        .param("page", "0")
                        .param("size", "10"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("a role without ROLES/READ must be denied 403 on the options endpoint")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("Token whose role holds ROLES/READ -> 200")
    void options_withReadGrant_returns200() throws Exception {
        RoleEntity role = persistRoleWithGrants(grant("ROLES", "READ"));

        MvcResult result = mockMvc.perform(get(ROLES_PATH)
                        .header("Authorization", bearer(role))
                        .param("sort", "name,asc")
                        .param("page", "0")
                        .param("size", "10"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("a role holding ROLES/READ must reach the options endpoint (200)")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("ADMIN token -> 200 via the evaluator bypass (no grant needed)")
    void options_admin_returns200() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(
                nextId(), "ADMIN", "admin+" + runId + "@example.com");

        MvcResult result = mockMvc.perform(get(ROLES_PATH)
                        .header("Authorization", "Bearer " + token)
                        .param("sort", "name,asc")
                        .param("page", "0")
                        .param("size", "10"))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("ADMIN must reach the options endpoint via the evaluator ADMIN bypass (200)")
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Reads the locale-resolved {@code name} of each option row in the returned page order. */
    private List<String> localizedNames(MvcResult result) throws Exception {
        JsonNode content = JSON.readTree(result.getResponse().getContentAsString()).get("content");
        List<String> names = new ArrayList<>();
        content.forEach(node -> names.add(node.get("name").asText()));
        return names;
    }

    /** A single (resource, {operations...}) grant specification for a role. */
    private record Grant(String resource, List<String> operations) {}

    private static Grant grant(String resource, String... operations) {
        return new Grant(resource, List.of(operations));
    }

    private String bearer(RoleEntity role) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(
                nextId(), role.getCode(), "user+" + runId + "@example.com");
    }

    /**
     * Persists (committed) a plain option row in the {@code roles} target table with the given
     * localized names. These rows are the dropdown options under test; they carry no grants.
     */
    private void persistOptionRole(String namePL, String nameRU) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            RoleEntity option = new RoleEntity();
            option.setCode("OPT_" + runId + "_" + COUNTER.incrementAndGet());
            option.setNamePL(namePL);
            option.setNameRU(nameRU);
            option.setSystem(false);
            entityManager.persist(option);
            entityManager.flush();
        });
    }

    /**
     * Persists (committed) a role with a unique per-run code holding exactly the supplied grants.
     * The evaluator matches on resource/operation <em>code</em>, so the shared ROLES/READ/CREATE
     * rows carry the exact matrix codes; only the role code carries the per-run suffix (it keys the
     * permission cache).
     */
    private RoleEntity persistRoleWithGrants(Grant... grants) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            RoleEntity role = new RoleEntity();
            role.setCode("REF_ROLE_" + runId + "_" + COUNTER.incrementAndGet());
            role.setNameRU("Роль REF");
            role.setNamePL("Rola REF");
            role.setSystem(false);

            List<RoleResourceEntity> roleResources = new ArrayList<>();
            for (Grant g : grants) {
                ResourceEntity resource = getOrCreateResource(g.resource());
                List<OperationEntity> ops = new ArrayList<>();
                for (String opCode : g.operations()) {
                    ops.add(getOrCreateOperation(opCode));
                }
                RoleResourceEntity rr = new RoleResourceEntity();
                rr.setRole(role);
                rr.setResource(resource);
                rr.setOperations(ops);
                roleResources.add(rr);
            }
            role.getRoleResources().addAll(roleResources);

            entityManager.persist(role);
            entityManager.flush();
            permissionCache.invalidate(role.getCode());
            return role;
        });
    }

    private ResourceEntity getOrCreateResource(String code) {
        List<ResourceEntity> existing = entityManager
                .createQuery("select r from ResourceEntity r where r.code = :c", ResourceEntity.class)
                .setParameter("c", code)
                .getResultList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        ResourceEntity resource = new ResourceEntity();
        resource.setCode(code);
        resource.setNameRU(code + " RU " + runId);
        resource.setNamePL(code + " PL " + runId);
        entityManager.persist(resource);
        return resource;
    }

    private OperationEntity getOrCreateOperation(String code) {
        List<OperationEntity> existing = entityManager
                .createQuery("select o from OperationEntity o where o.code = :c", OperationEntity.class)
                .setParameter("c", code)
                .getResultList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        OperationEntity operation = new OperationEntity();
        operation.setCode(code);
        operation.setNameRU(code + " RU " + runId);
        operation.setNamePL(code + " PL " + runId);
        entityManager.persist(operation);
        return operation;
    }

    private long nextId() {
        return 940_000L + COUNTER.incrementAndGet();
    }
}
