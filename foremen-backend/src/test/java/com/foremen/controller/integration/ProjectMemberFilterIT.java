package com.foremen.controller.integration;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * End-to-end integration test for the nested {@code project_members} filtering on the projects list
 * (FOR-04-13, task 9.4). It exercises the real Spring MVC + Spring Security + JPA/Hibernate stack
 * against a Testcontainers PostgreSQL, proving the FOR-04-01 nested reference-filter grammar produces
 * the correct JOIN + {@code distinct} against the live {@code project_members} join table and that
 * the list/read DTOs expose the projected {@code members} array and the derived {@code client}.
 *
 * <p>The harness mirrors {@link ReferenceFilterQueryIntegrationTest}
 * ({@code com.foremen.integration}) and {@link OfferPackageControllerIntegrationTest}: it uses
 * {@code @SpringBootTest(MOCK)} + {@code @AutoConfigureMockMvc} + {@code @Testcontainers} +
 * {@code @ActiveProfiles("integration-test")} so Liquibase is disabled and Hibernate
 * {@code create-drop} builds the schema. Every projects/users/roles/{@code project_members} row is
 * created programmatically and removed in {@link #cleanUp()}, so the suite re-runs without manual
 * cleanup.
 *
 * <p>Reads use an <b>ADMIN</b> token so {@code ForemenPermissionEvaluator} bypasses the ABAC matrix
 * <em>and</em> the {@code project_members} membership filtering — the returned set is governed purely
 * by the requested {@code query} filter rather than the caller's own memberships (Requirement 4.3),
 * isolating these assertions to the nested-filter behaviour.
 *
 * <p>Scenarios (Requirements 8.1, 8.2, 3.4):
 * <ul>
 *   <li>The plain member filter {@code members.user.id~in~<ids>} returns exactly the projects that
 *       have at least one matching member, each project exactly once (the to-many join fan-out is
 *       collapsed via {@code distinct}).</li>
 *   <li>The compound client filter {@code members.user.id~in~<ids> AND
 *       members.projectRole.code==CLIENT} returns exactly the projects whose <b>CLIENT</b> member is
 *       one of the selected users, excluding projects that match the same user set only through a
 *       non-CLIENT member.</li>
 *   <li>The list and read DTO JSON expose the {@code members} array and the derived {@code client}
 *       (the single CLIENT member, or {@code null} when none).</li>
 * </ul>
 *
 * <p>Validates: Requirements 8.1, 8.2, 3.4
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
class ProjectMemberFilterIT {

    private static final String PROJECTS_PATH = "/api/projects";

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
        // Delete this run's rows in FK order: members -> projects -> users -> roles.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery(
                            "delete from ProjectMemberEntity m where m.user.email like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from ProjectEntity p where p.name like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from UserEntity u where u.email like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
            entityManager.createQuery("delete from RoleEntity r where r.code like :p")
                    .setParameter("p", "%" + runId + "%")
                    .executeUpdate();
        });
    }

    // ------------------------------------------------------------------
    // Nested member filter: members.user.id~in~<ids> (Requirement 8.2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("members.user.id~in~<ids> returns exactly the matching projects, deduplicated")
    void memberFilter_returnsExactlyMatchingProjectsDeduplicated() throws Exception {
        RoleEntity foreman = persistRole("FOREMAN");
        RoleEntity worker = persistRole("WORKER");

        UserEntity alice = persistUser("Alice", foreman);
        UserEntity bob = persistUser("Bob", foreman);
        UserEntity carol = persistUser("Carol", worker);

        // pAlice: Alice is a member (matches). Two member rows for Alice's project so the to-many
        // join fan-out must be collapsed to a single row by distinct.
        Long pAlice = persistProject("P-Alice");
        assignMember(alice, pAlice, foreman);
        assignMember(bob, pAlice, foreman);

        // pBoth: both Alice and Bob are members — again would fan-out; distinct must collapse.
        Long pBoth = persistProject("P-Both");
        assignMember(alice, pBoth, foreman);
        assignMember(bob, pBoth, worker);

        // pCarol: only Carol is a member — must be excluded from a filter selecting {Alice}.
        Long pCarol = persistProject("P-Carol");
        assignMember(carol, pCarol, worker);

        // pEmpty: no members — must be excluded.
        Long pEmpty = persistProject("P-Empty");

        MvcResult result = mockMvc.perform(get(PROJECTS_PATH)
                        .header("Authorization", adminBearer())
                        .param("query", "members.user.id~in~" + alice.getId())
                        .param("page", "0")
                        .param("size", "50"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        List<Long> ids = idsOf(result);
        assertThat(ids)
                .as("members.user.id~in~<alice> must return exactly the projects Alice belongs to, "
                        + "each exactly once (distinct collapses the to-many join fan-out)")
                .containsExactlyInAnyOrder(pAlice, pBoth)
                .doesNotHaveDuplicates()
                .doesNotContain(pCarol, pEmpty);
    }

    @Test
    @DisplayName("members.user.id~in~<a>,<b> returns the union of both users' projects, deduplicated")
    void memberFilter_multiSelect_returnsUnionDeduplicated() throws Exception {
        RoleEntity foreman = persistRole("FOREMAN");

        UserEntity alice = persistUser("Alice", foreman);
        UserEntity bob = persistUser("Bob", foreman);
        UserEntity dave = persistUser("Dave", foreman);

        Long pAlice = persistProject("P-Alice");
        assignMember(alice, pAlice, foreman);

        Long pBob = persistProject("P-Bob");
        assignMember(bob, pBob, foreman);

        // pShared has both Alice and Bob — a multi-select ~in~ join fans out to two rows; distinct
        // must return the shared project only once.
        Long pShared = persistProject("P-Shared");
        assignMember(alice, pShared, foreman);
        assignMember(bob, pShared, foreman);

        // pDave: neither Alice nor Bob — excluded.
        Long pDave = persistProject("P-Dave");
        assignMember(dave, pDave, foreman);

        MvcResult result = mockMvc.perform(get(PROJECTS_PATH)
                        .header("Authorization", adminBearer())
                        .param("query", "members.user.id~in~" + alice.getId() + "," + bob.getId())
                        .param("page", "0")
                        .param("size", "50"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        List<Long> ids = idsOf(result);
        assertThat(ids)
                .as("members.user.id~in~<alice>,<bob> must return the union of their projects, "
                        + "the shared project only once")
                .containsExactlyInAnyOrder(pAlice, pBob, pShared)
                .doesNotHaveDuplicates()
                .doesNotContain(pDave);
    }

    // ------------------------------------------------------------------
    // Compound client filter (Requirements 8.1, 8.2)
    // members.user.id~in~<ids> AND members.projectRole.code==CLIENT
    // ------------------------------------------------------------------

    @Test
    @DisplayName("compound client filter returns only projects whose CLIENT member matches, "
            + "excluding non-CLIENT-only matches")
    void clientFilter_returnsOnlyProjectsWhoseClientMatches() throws Exception {
        RoleEntity clientRole = persistRole("CLIENT");
        RoleEntity foreman = persistRole("FOREMAN");

        // Two users that are both selected in the ~in~ set.
        UserEntity clientUser = persistUser("ClientUser", clientRole);
        UserEntity workerUser = persistUser("WorkerUser", foreman);

        // pClient: clientUser is attached as CLIENT -> MUST match the compound filter.
        Long pClient = persistProject("P-Client");
        assignMember(clientUser, pClient, clientRole);

        // pNonClientOnly: clientUser is attached, but under a NON-CLIENT role (foreman). It matches
        // the user set, but NOT the CLIENT-role conjunct -> MUST be excluded by the compound filter.
        Long pNonClientOnly = persistProject("P-NonClientOnly");
        assignMember(clientUser, pNonClientOnly, foreman);

        // pOtherClient: has a CLIENT member, but that member (workerUser) is in the selected set only
        // via a non-CLIENT membership elsewhere; here workerUser IS the CLIENT of this project, and is
        // in the ~in~ set, so this MUST also match.
        Long pOtherClient = persistProject("P-OtherClient");
        assignMember(workerUser, pOtherClient, clientRole);

        // pForemanOnly: workerUser as foreman only -> matches user set, not CLIENT -> excluded.
        Long pForemanOnly = persistProject("P-ForemanOnly");
        assignMember(workerUser, pForemanOnly, foreman);

        String query = "members.user.id~in~" + clientUser.getId() + "," + workerUser.getId()
                + " AND members.projectRole.code==CLIENT";

        MvcResult result = mockMvc.perform(get(PROJECTS_PATH)
                        .header("Authorization", adminBearer())
                        .param("query", query)
                        .param("page", "0")
                        .param("size", "50"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        List<Long> ids = idsOf(result);
        assertThat(ids)
                .as("the compound client filter must return exactly the projects whose CLIENT member "
                        + "is one of the selected users, excluding projects matching only via a "
                        + "non-CLIENT member")
                .containsExactlyInAnyOrder(pClient, pOtherClient)
                .doesNotHaveDuplicates()
                .doesNotContain(pNonClientOnly, pForemanOnly);
    }

    // ------------------------------------------------------------------
    // DTO exposure: members array + derived client (Requirement 3.4)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("list/read DTO exposes the members array and the derived client")
    void listAndReadDto_exposeMembersAndDerivedClient() throws Exception {
        RoleEntity clientRole = persistRole("CLIENT");
        RoleEntity foreman = persistRole("FOREMAN");

        UserEntity clientUser = persistUser("ClientUser", clientRole);
        UserEntity foremanUser = persistUser("ForemanUser", foreman);

        Long project = persistProject("P-Dto");
        assignMember(foremanUser, project, foreman);
        assignMember(clientUser, project, clientRole);

        // --- LIST DTO: members[] present, client derived to the CLIENT member ---
        MvcResult listResult = mockMvc.perform(get(PROJECTS_PATH)
                        .header("Authorization", adminBearer())
                        .param("query", "members.user.id~in~" + clientUser.getId())
                        .param("page", "0")
                        .param("size", "50"))
                .andReturn();

        assertThat(listResult.getResponse().getStatus()).isEqualTo(200);

        JsonNode content = JSON.readTree(listResult.getResponse().getContentAsString()).get("content");
        JsonNode row = null;
        for (JsonNode node : content) {
            if (node.get("id").asLong() == project) {
                row = node;
                break;
            }
        }
        assertThat(row).as("the seeded project must be present in the list result").isNotNull();

        JsonNode members = row.get("members");
        assertThat(members).as("list DTO exposes a members array").isNotNull();
        assertThat(members.isArray()).isTrue();
        List<Long> memberUserIds = new ArrayList<>();
        members.forEach(m -> memberUserIds.add(m.get("userId").asLong()));
        assertThat(memberUserIds)
                .as("members array carries both the foreman and the client member")
                .containsExactlyInAnyOrder(foremanUser.getId(), clientUser.getId());

        JsonNode client = row.get("client");
        assertThat(client).as("list DTO exposes a derived client field").isNotNull();
        assertThat(client.isNull())
                .as("derived client must be populated (project has a CLIENT member)")
                .isFalse();
        assertThat(client.get("userId").asLong())
                .as("derived client is the CLIENT member, not the foreman")
                .isEqualTo(clientUser.getId());
        assertThat(client.get("roleCode").asText()).isEqualTo("CLIENT");

        // --- READ DTO: same shape (members[] + derived client) ---
        MvcResult readResult = mockMvc.perform(get(PROJECTS_PATH + "/" + project)
                        .header("Authorization", adminBearer()))
                .andReturn();

        assertThat(readResult.getResponse().getStatus()).isEqualTo(200);

        JsonNode read = JSON.readTree(readResult.getResponse().getContentAsString());
        JsonNode readMembers = read.get("members");
        assertThat(readMembers).as("read DTO exposes a members array").isNotNull();
        List<Long> readMemberUserIds = new ArrayList<>();
        readMembers.forEach(m -> readMemberUserIds.add(m.get("userId").asLong()));
        assertThat(readMemberUserIds)
                .as("read DTO members array carries both members")
                .containsExactlyInAnyOrder(foremanUser.getId(), clientUser.getId());

        JsonNode readClient = read.get("client");
        assertThat(readClient).as("read DTO exposes a derived client field").isNotNull();
        assertThat(readClient.isNull()).isFalse();
        assertThat(readClient.get("userId").asLong()).isEqualTo(clientUser.getId());
        assertThat(readClient.get("roleCode").asText()).isEqualTo("CLIENT");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Extracts the {@code id} of each project row in the returned page. */
    private List<Long> idsOf(MvcResult result) throws Exception {
        JsonNode content = JSON.readTree(result.getResponse().getContentAsString()).get("content");
        List<Long> ids = new ArrayList<>();
        content.forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }

    /** ADMIN bearer token — the evaluator bypasses both the ABAC matrix and membership filtering. */
    private String adminBearer() {
        return "Bearer " + jwtTokenProvider.generateAccessToken(
                nextId(), "ADMIN", "admin+" + runId + "@example.com");
    }

    /**
     * Provides a role for seeding memberships.
     *
     * <p>The compound client filter's second conjunct is the <em>exact</em> match
     * {@code members.projectRole.code==CLIENT}, so the client role must have the literal code
     * {@code CLIENT}. That role already exists (seeded by the FOR-03 role data / persisted by earlier
     * tests) and its {@code code} is globally unique, so this reuses the existing {@code CLIENT} row
     * (find-or-create) rather than inserting a duplicate that would violate {@code roles_code_key}.
     * Every other role is created fresh under a unique per-run code so the suite re-runs without
     * collisions.
     */
    private RoleEntity persistRole(String namePrefix) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            if ("CLIENT".equals(namePrefix)) {
                List<RoleEntity> existing = entityManager
                        .createQuery("select r from RoleEntity r where r.code = 'CLIENT'", RoleEntity.class)
                        .setMaxResults(1)
                        .getResultList();
                if (!existing.isEmpty()) {
                    return existing.get(0);
                }
                RoleEntity clientRole = new RoleEntity();
                clientRole.setCode("CLIENT");
                clientRole.setNameRU("Клиент");
                clientRole.setNamePL("Klient");
                clientRole.setSystem(false);
                entityManager.persist(clientRole);
                entityManager.flush();
                return clientRole;
            }
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

    /** Persists (committed) a user referencing the given company role; email carries the run id. */
    private UserEntity persistUser(String name, RoleEntity role) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            RoleEntity attached = entityManager.find(RoleEntity.class, role.getId());
            UserEntity user = new UserEntity();
            user.setName(name + " " + runId);
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

    /** Persists (committed) a project whose name carries the run id; returns its generated id. */
    private Long persistProject(String namePrefix) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            ProjectEntity project = new ProjectEntity();
            project.setName(namePrefix + " " + runId + " " + COUNTER.incrementAndGet());
            project.setStatus(ProjectStatus.ACTIVE);
            entityManager.persist(project);
            entityManager.flush();
            return project.getId();
        });
    }

    /** Persists (committed) a {@code project_members} row joining the user to the project under a role. */
    private void assignMember(UserEntity user, Long projectId, RoleEntity projectRole) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            UserEntity attachedUser = entityManager.find(UserEntity.class, user.getId());
            RoleEntity attachedRole = entityManager.find(RoleEntity.class, projectRole.getId());
            ProjectMemberEntity member = new ProjectMemberEntity();
            member.setUser(attachedUser);
            member.setProjectId(projectId);
            member.setProjectRole(attachedRole);
            entityManager.persist(member);
            entityManager.flush();
        });
    }

    private long nextId() {
        return 970_000L + COUNTER.incrementAndGet();
    }
}
