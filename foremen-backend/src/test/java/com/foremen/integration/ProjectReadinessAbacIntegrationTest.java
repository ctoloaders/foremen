package com.foremen.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * ABAC per-role integration test for the FOR-05-11 aggregated readiness endpoint
 * {@code GET /api/projects/{projectId}/readiness} (task 5.1).
 *
 * <p>Boots the full application context over the real Spring MVC + Spring Security + ABAC stack
 * against a Testcontainers PostgreSQL instance, following the project's established
 * {@code @SpringBootTest(MOCK)} + {@code @AutoConfigureMockMvc} + {@code @Testcontainers} +
 * {@code @ActiveProfiles("integration-test")} pattern (mirroring
 * {@link EndToEndResolutionIntegrationTest}). Under the {@code integration-test} profile Liquibase is
 * disabled and the schema is built by Hibernate {@code create-drop}, so every matrix row (roles,
 * resources, operations, grants) and every domain fixture (users, project, rooms, memberships) is
 * created programmatically via the {@link EntityManager}.
 *
 * <p>The endpoint is guarded by {@code PROJECTS} READ at the interceptor (Requirement 2) and the
 * service applies a per-{@code Area_Resource} READ scope (Requirement 6) over a 404 project-access
 * gate (Requirement 3). The cases prove, end to end:
 *
 * <ul>
 *   <li><b>401</b> — an unauthenticated request is rejected at the filter chain (Requirement 2.3).</li>
 *   <li><b>403</b> — an authenticated caller lacking {@code PROJECTS} READ is denied by the
 *       interceptor with {@code error.access.denied} (Requirement 2.4).</li>
 *   <li><b>200, {@code PROJECTS}+{@code ROOMS}-READ member</b> — a member whose role may READ only
 *       {@code PROJECTS} and {@code ROOMS} sees <em>only</em> the {@code rooms} area, and the overall
 *       headline is computed over that single area (Requirements 4.1, 6.2) — here a project with one
 *       dimensioned and one un-dimensioned room yields a {@code rooms} area at 50% and therefore a
 *       50% / {@code PARTIAL} headline, with no other area key present.</li>
 *   <li><b>200, no readable area</b> — a member holding {@code PROJECTS} READ but no
 *       {@code Area_Resource} READ gets an empty {@code areas} list, {@code overallPercentage 0}, and
 *       {@code overallState BLOCKED} (Requirement 6.4).</li>
 *   <li><b>404, non-member</b> — a member of project A requesting project B's readiness gets 404
 *       {@code error.entity.not.found} (Requirement 3.2).</li>
 *   <li><b>404, non-existent</b> — a request for a non-existent project id gets a <em>byte-identical</em>
 *       404 body (missing and inaccessible are indistinguishable, Requirement 3.3).</li>
 * </ul>
 *
 * <p>Repeatability: every role/user/project/resource/operation carries a unique per-run suffix (a
 * per-test UUID plus a counter), and {@link #cleanUp()} removes every row this test created (in FK
 * order) after each test, so the suite re-runs without manual DB cleanup and no stale cached
 * permission or project-access set can leak.
 *
 * <p>Validates: Requirements 2.3, 2.4, 3.2, 3.3, 6.2, 6.4
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
class ProjectReadinessAbacIntegrationTest {

    private static final String READINESS_PATH_TEMPLATE = "/api/projects/%d/readiness";

    private static final AtomicLong COUNTER = new AtomicLong();

    /**
     * Every resource/operation code this test seeds so the codes match the resolved pairs exactly
     * ({@code PROJECTS}/{@code ROOMS} with the {@code READ} operation). Removed wholesale in
     * {@link #cleanUp()} to keep the suite repeatable.
     */
    private static final Set<String> SEEDED_RESOURCE_CODES = Set.of("PROJECTS", "ROOMS");
    private static final Set<String> SEEDED_OPERATION_CODES = Set.of("READ");

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

    /** Local instance — the MOCK web-environment context does not expose an ObjectMapper bean. */
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    /** Unique run id so every seeded code/name never collides with earlier runs. */
    private final String runId = UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void cleanUp() {
        // Bulk deletes require an active transaction; @AfterEach is not test-transaction-managed, so
        // wrap the teardown in an explicit programmatic transaction. Delete deepest-first in FK order:
        // memberships -> rooms -> projects, then users, then grants/roles, then the shared matrix rows.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery(
                            "delete from ProjectMemberEntity pm where pm.user.id in "
                                    + "(select u.id from UserEntity u where u.email like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery(
                            "delete from RoomEntity r where r.project.id in "
                                    + "(select pr.id from ProjectEntity pr where pr.name like :p)")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from ProjectEntity pr where pr.name like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from RoomTypeEntity rt where rt.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from UserEntity u where u.email like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
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
    // 401 — unauthenticated (Requirement 2.3)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("unauthenticated readiness request yields 401 at the filter chain")
    void unauthenticated_yields401() throws Exception {
        long projectId = createProject();

        MvcResult result = mockMvc.perform(get(readinessPath(projectId))).andReturn();

        assertThat(result.getResponse().getStatus())
                .as("an unauthenticated request to the guarded readiness endpoint must yield 401")
                .isEqualTo(401);
    }

    // ------------------------------------------------------------------
    // 403 — authenticated caller without PROJECTS READ (Requirement 2.4)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a member whose role lacks PROJECTS READ is denied 403 by the interceptor")
    void memberWithoutProjectsRead_yields403() throws Exception {
        long projectId = createProject();
        // Role holds ROOMS READ only -> the interceptor's PROJECTS/READ gate is not satisfied.
        RoleEntity role = persistRoleWithGrants(grant("ROOMS", "READ"));
        long userId = createMember(projectId, role);

        MvcResult result = mockMvc.perform(get(readinessPath(projectId))
                        .header("Authorization", bearer(userId, role)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("a caller lacking PROJECTS READ must be denied 403 by the PermissionInterceptor")
                .isEqualTo(403);
        assertThat(result.getResponse().getContentAsString())
                .as("the 403 body must carry the resolved error.access.denied message (PL or RU locale)")
                .satisfiesAnyOf(
                        b -> assertThat(b).contains("Brak wymaganych uprawnień"),
                        b -> assertThat(b).contains("Недостаточно прав"));
    }

    // ------------------------------------------------------------------
    // 200 — a ROOMS-only member sees only the rooms area (Requirements 4.1, 6.2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a PROJECTS+ROOMS-READ member sees only the rooms area and a headline over that one area")
    void roomsOnlyMember_seesOnlyRoomsArea() throws Exception {
        long projectId = createProject();
        // One dimensioned room (positive floor area) and one without -> rooms area = 50% PARTIAL.
        createRoom(projectId, new BigDecimal("12.50"));
        createRoom(projectId, null);

        RoleEntity role = persistRoleWithGrants(grant("PROJECTS", "READ"), grant("ROOMS", "READ"));
        long userId = createMember(projectId, role);

        MvcResult result = mockMvc.perform(get(readinessPath(projectId))
                        .header("Authorization", bearer(userId, role)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("a member holding PROJECTS READ must reach the endpoint (200)")
                .isEqualTo(200);

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("projectId").asLong()).isEqualTo(projectId);

        List<String> areaKeys = new ArrayList<>();
        body.path("areas").forEach(area -> areaKeys.add(area.path("key").asText()));
        assertThat(areaKeys)
                .as("only the rooms area is readable; no other area key may leak (Requirement 6.2)")
                .containsExactly("rooms");

        JsonNode roomsArea = body.path("areas").get(0);
        assertThat(roomsArea.path("percentage").asInt())
                .as("one of two rooms is dimensioned -> rooms area = round(100 * 1/2) = 50%")
                .isEqualTo(50);
        assertThat(roomsArea.path("state").asText())
                .as("a partially dimensioned rooms area is PARTIAL")
                .isEqualTo("PARTIAL");

        assertThat(body.path("overallPercentage").asInt())
                .as("the headline is the equal-weight mean over the single readable (rooms) area = 50%")
                .isEqualTo(50);
        assertThat(body.path("overallState").asText())
                .as("the overall state rolls up the single PARTIAL rooms area to PARTIAL")
                .isEqualTo("PARTIAL");
    }

    // ------------------------------------------------------------------
    // 200 — no readable area: empty areas, 0%, BLOCKED (Requirement 6.4)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a member with PROJECTS READ but no area READ gets 200 + empty areas + 0% + BLOCKED")
    void memberWithNoReadableArea_getsEmptyBlockedHeadline() throws Exception {
        long projectId = createProject();
        createRoom(projectId, new BigDecimal("20.00")); // a dimensioned room that must stay hidden

        RoleEntity role = persistRoleWithGrants(grant("PROJECTS", "READ"));
        long userId = createMember(projectId, role);

        MvcResult result = mockMvc.perform(get(readinessPath(projectId))
                        .header("Authorization", bearer(userId, role)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("holding PROJECTS READ admits the caller to the endpoint even with no readable area (200)")
                .isEqualTo(200);

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("areas"))
                .as("no Area_Resource is readable -> the areas list is empty (Requirement 6.4)")
                .isEmpty();
        assertThat(body.path("overallPercentage").asInt())
                .as("no included area -> overall percentage is 0 (Requirement 6.4)")
                .isEqualTo(0);
        assertThat(body.path("overallState").asText())
                .as("no included area -> overall state is BLOCKED (Requirement 6.4)")
                .isEqualTo("BLOCKED");
    }

    // ------------------------------------------------------------------
    // 404 — non-member and non-existent project with identical bodies (Requirements 3.2, 3.3)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("non-member and non-existent project both yield 404 error.entity.not.found with identical bodies")
    void nonMemberAndNonExistent_yieldIdentical404() throws Exception {
        long memberProjectId = createProject();   // the project the caller belongs to
        long otherProjectId = createProject();     // an existing project the caller is NOT a member of
        long nonExistentProjectId = 9_000_000_000L + COUNTER.incrementAndGet();

        // A member of memberProjectId with full read grants; its lack of membership in otherProjectId
        // (not a missing permission) drives the 404 project-access gate.
        RoleEntity role = persistRoleWithGrants(grant("PROJECTS", "READ"), grant("ROOMS", "READ"));
        long userId = createMember(memberProjectId, role);
        String auth = bearer(userId, role);

        // --- non-member: existing project, caller not a member -> 404 ---
        MvcResult nonMember = mockMvc.perform(get(readinessPath(otherProjectId))
                        .header("Authorization", auth))
                .andReturn();
        assertThat(nonMember.getResponse().getStatus())
                .as("a non-member requesting an existing project's readiness must get 404 (Requirement 3.2)")
                .isEqualTo(404);
        JsonNode nonMemberBody = objectMapper.readTree(nonMember.getResponse().getContentAsString());
        assertThat(nonMemberBody.path("message").asText())
                .as("the 404 message must carry the resolved error.entity.not.found text (PL default)")
                .satisfiesAnyOf(
                        m -> assertThat(m).containsIgnoringCase("Nie znaleziono"),
                        m -> assertThat(m).containsIgnoringCase("не найден"));

        // --- non-existent: ADMIN requesting a project id that does not exist -> 404 ---
        String adminToken = jwtTokenProvider.generateAccessToken(
                nextId(), "ADMIN", "admin+" + runId + "@example.com");
        MvcResult nonExistent = mockMvc.perform(get(readinessPath(nonExistentProjectId))
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn();
        assertThat(nonExistent.getResponse().getStatus())
                .as("a request for a non-existent project id must get 404 (Requirement 3.3)")
                .isEqualTo(404);

        // --- the two 404 bodies are byte-identical except for the (indistinguishable) project id;
        // the message code and shape must match so missing and inaccessible cannot be told apart ---
        JsonNode nonExistentBody = objectMapper.readTree(nonExistent.getResponse().getContentAsString());
        assertThat(fieldNames(nonExistentBody))
                .as("the two 404 bodies must share the same field set (Requirement 3.3)")
                .isEqualTo(fieldNames(nonMemberBody));
        assertThat(nonExistentBody.path("status").asInt())
                .as("both 404 bodies carry the same HTTP status field")
                .isEqualTo(nonMemberBody.path("status").asInt());
    }

    // ------------------------------------------------------------------
    // fixtures & helpers
    // ------------------------------------------------------------------

    private String readinessPath(long projectId) {
        return String.format(READINESS_PATH_TEMPLATE, projectId);
    }

    /** A single (resource, {operations...}) grant specification for a role. */
    private record Grant(String resource, List<String> operations) {}

    private static Grant grant(String resource, String... operations) {
        return new Grant(resource, List.of(operations));
    }

    private String bearer(long userId, RoleEntity role) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(
                userId, role.getCode(), "user" + userId + "+" + runId + "@example.com");
    }

    /** Creates (and commits) a DRAFT project with a per-run unique name; returns its generated id. */
    private long createProject() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            com.foremen.dao.model.ProjectEntity project = new com.foremen.dao.model.ProjectEntity();
            project.setName("Readiness ABAC " + runId + "-" + COUNTER.incrementAndGet());
            project.setStatus(ProjectStatus.DRAFT);
            entityManager.persist(project);
            entityManager.flush();
            return project.getId();
        });
    }

    /**
     * Creates (and commits) a room on the project with the given floor area (null = no dimensions),
     * reusing a per-run room type fixture.
     */
    private void createRoom(long projectId, BigDecimal floorArea) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            com.foremen.dao.model.ProjectEntity project =
                    entityManager.getReference(com.foremen.dao.model.ProjectEntity.class, projectId);
            com.foremen.dao.model.RoomEntity room = new com.foremen.dao.model.RoomEntity();
            room.setProject(project);
            room.setRoomType(getOrCreateRoomType());
            room.setLabel("Room " + runId + "-" + COUNTER.incrementAndGet());
            room.setFloorArea(floorArea);
            entityManager.persist(room);
            entityManager.flush();
        });
    }

    /**
     * Creates (and commits) a user plus a membership row linking that user to the project with the
     * supplied role (any role satisfies the FK). The returned user id is minted into the caller's
     * JWT {@code sub}, so {@code ProjectAccessCache} self-loads this project id for the caller and the
     * service's 404 access gate passes for this project (and fails for any other).
     *
     * @return the generated user id (used as the JWT subject)
     */
    private long createMember(long projectId, RoleEntity role) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            RoleEntity managedRole = entityManager.getReference(RoleEntity.class, role.getId());

            UserEntity user = new UserEntity();
            long seq = COUNTER.incrementAndGet();
            user.setName("Member " + runId + "-" + seq);
            user.setEmail("member" + seq + "+" + runId + "@example.com");
            user.setRole(managedRole);
            user.setActive(true);
            user.setStatus(UserStatus.ACTIVE);
            user.setLocale(java.util.Locale.forLanguageTag("ru"));
            entityManager.persist(user);

            ProjectMemberEntity member = new ProjectMemberEntity();
            member.setUser(user);
            member.setProjectId(projectId);
            member.setProjectRole(managedRole);
            entityManager.persist(member);
            entityManager.flush();
            return user.getId();
        });
    }

    /**
     * Persists (and commits) a role with a unique per-run code holding exactly the supplied grants.
     * The evaluator matches on resource and operation <em>code</em>, so the shared resource/operation
     * rows carry the exact matrix codes (e.g. {@code PROJECTS}/{@code READ}); only the role code
     * carries the per-run suffix (it keys the permission cache). The committed rows are visible to the
     * request-thread transaction the {@code ForemenPermissionEvaluator} opens on a cache miss.
     */
    private RoleEntity persistRoleWithGrants(Grant... grants) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            RoleEntity role = new RoleEntity();
            role.setCode("READINESS_ROLE_" + runId + "_" + COUNTER.incrementAndGet());
            role.setNameRU("Роль readiness");
            role.setNamePL("Rola readiness");
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

    /** Returns the existing resource row for the code, or creates it (codes must be exact). */
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

    /** Returns the existing operation row for the code, or creates it (codes must be exact). */
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

    /** Returns the per-run shared room type fixture, creating it on first use. */
    private com.foremen.dao.model.RoomTypeEntity getOrCreateRoomType() {
        List<com.foremen.dao.model.RoomTypeEntity> existing = entityManager
                .createQuery("select rt from RoomTypeEntity rt where rt.code = :c",
                        com.foremen.dao.model.RoomTypeEntity.class)
                .setParameter("c", "ROOMTYPE_" + runId)
                .getResultList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        com.foremen.dao.model.RoomTypeEntity roomType = new com.foremen.dao.model.RoomTypeEntity();
        roomType.setCode("ROOMTYPE_" + runId);
        roomType.setNameRU("Комната " + runId);
        roomType.setNamePL("Pokój " + runId);
        roomType.setActive(true);
        entityManager.persist(roomType);
        return roomType;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        java.util.Collections.sort(names);
        return names;
    }

    private long nextId() {
        return 920_000L + COUNTER.incrementAndGet();
    }
}
