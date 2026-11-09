package com.foremen.integration;

import com.foremen.config.security.JwtTokenProvider;
import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.DashboardController;
import com.foremen.dao.model.DashboardWidgetEntity;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.service.permission.PermissionCache;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * FOR-10 task 2.6 — {@code /api/dashboard} over the real MVC + security + ABAC stack against
 * Testcontainers PostgreSQL. The context loading at all proves {@code PermissionAnnotationValidator}
 * accepts the self-service {@link DashboardController} (no permission annotations).
 *
 * <p>Roles: ADMIN (matrix bypass, no project scope), MANAGER (PROJECTS READ, member of no project),
 * FOREMAN (no PROJECTS grant). Repeatability: every row carries a per-run id; {@link #cleanUp()}
 * removes placements, dashboard state, the widget, the project, the users and the matrix rows.
 *
 * <p><b>Validates: Requirements 2.5, 6.3, 6.7, 8.3, 9.1, 9.3, 9.5, 12.2</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
class DashboardControllerIntegrationTest {

    private static final String BASE = "/api/dashboard";
    private static final Set<String> ROLE_CODES = Set.of("ADMIN", "MANAGER", "FOREMAN");

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
    private ApplicationContext applicationContext;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private PermissionCache permissionCache;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @PersistenceContext
    private EntityManager em;

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    private final LocalDate today = LocalDate.now();
    private RoleEntity adminRole;
    private RoleEntity managerRole;
    private RoleEntity foremanRole;
    private long adminId;
    private long managerId;
    private long foremanId;
    private long projectId;
    private long widgetId;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void setUp() {
        adminRole = ensureRole("ADMIN", false);
        managerRole = ensureRole("MANAGER", true);
        foremanRole = ensureRole("FOREMAN", false);
        adminId = persistUser("admin", adminRole);
        managerId = persistUser("manager", managerRole);
        foremanId = persistUser("foreman", foremanRole);
        tx().executeWithoutResult(s -> {
            ProjectEntity p = new ProjectEntity();
            p.setName("Dash " + runId);
            p.setAddress("ul. Testowa " + runId);
            p.setStatus(ProjectStatus.ACTIVE);
            p.setStartDate(today);
            em.persist(p);
            em.flush();
            projectId = p.getId();
            DashboardWidgetEntity w = new DashboardWidgetEntity();
            w.setCode("IT_" + runId);
            w.setNameRu("Проекты " + runId);
            w.setNamePl("Projekty " + runId);
            w.setConfig(Map.of(
                    "schemaVersion", 1,
                    "dataset", "PROJECTS",
                    "visualization", "TABLE",
                    "columns", List.of(Map.of("field", "name",
                            "label", Map.of("ru", "Проект", "pl", "Projekt"))),
                    "filters", List.of(Map.of("field", "name", "op", "EQ", "value", "Dash " + runId))));
            w.setConfigVersion(1);
            w.setColSpan((short) 2);
            w.setRowSpan((short) 2);
            w.setProjectScope("NONE");
            w.setActive(true);
            w.setSystemSeed(false);
            em.persist(w);
            em.flush();
            widgetId = w.getId();
        });
    }

    @AfterEach
    void cleanUp() {
        tx().executeWithoutResult(s -> {
            List<Long> users = List.of(adminId, managerId, foremanId);
            em.createQuery("delete from UserDashboardWidgetEntity p where p.user.id in :u")
                    .setParameter("u", users).executeUpdate();
            em.createQuery("delete from UserDashboardStateEntity st where st.userId in :u")
                    .setParameter("u", users).executeUpdate();
            em.createQuery("delete from DashboardWidgetEntity w where w.id = :id")
                    .setParameter("id", widgetId).executeUpdate();
            em.createQuery("delete from ProjectEntity p where p.id = :id")
                    .setParameter("id", projectId).executeUpdate();
            em.createQuery("delete from UserEntity u where u.id in :u").setParameter("u", users).executeUpdate();
            em.createQuery("delete from RoleResourceEntity rr where rr.role.id in "
                            + "(select r.id from RoleEntity r where r.code in :c)")
                    .setParameter("c", ROLE_CODES).executeUpdate();
        });
        ROLE_CODES.forEach(permissionCache::invalidate);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("context loads with the self-service DashboardController (no permission annotations)")
    void selfServiceControllerPassesStartupValidation() {
        assertThat(applicationContext.getBean(DashboardController.class)).isNotNull();
        assertThat(DashboardController.class.isAnnotationPresent(PermissionResource.class)).isFalse();
        for (Method m : DashboardController.class.getDeclaredMethods()) {
            assertThat(m.isAnnotationPresent(PermissionOperation.class)).isFalse();
            assertThat(m.isAnnotationPresent(RequiresPermission.class)).isFalse();
        }
    }

    @Test
    void unauthenticatedIs401() throws Exception {
        assertThat(status(get(BASE))).isEqualTo(401);
        assertThat(status(get(BASE + "/library"))).isEqualTo(401);
    }

    @Test
    @DisplayName("admin adds a widget, sees it in the layout and fetches its data; dates are validated")
    void adminAddLayoutAndFetch() throws Exception {
        String auth = bearer(adminId, adminRole);
        long placementId = addPlacement(auth, 201);

        String layout = body(get(BASE).header("Authorization", auth), 200);
        List<Integer> ids = JsonPath.read(layout, "$.items[*].id");
        assertThat(ids).contains((int) placementId);
        List<String> states = JsonPath.read(layout, "$.items[?(@.id == " + placementId + ")].state");
        assertThat(states).containsExactly("AVAILABLE");

        String data = body(get(BASE + "/items/" + placementId + "/data").header("Authorization", auth)
                .param("dateFrom", today.minusDays(1).toString()).param("dateTo", today.plusDays(1).toString()), 200);
        List<String> names = JsonPath.read(data, "$.rows[*].name");
        assertThat(names).containsExactly("Dash " + runId);
        assertThat((Boolean) JsonPath.read(data, "$.truncated")).isFalse();

        // the date range excludes the project's start date -> no rows
        String outside = body(get(BASE + "/items/" + placementId + "/data").header("Authorization", auth)
                .param("dateFrom", today.plusDays(5).toString()).param("dateTo", today.plusDays(6).toString()), 200);
        assertThat((List<?>) JsonPath.read(outside, "$.rows")).isEmpty();

        assertThat(status(get(BASE + "/items/" + placementId + "/data").header("Authorization", auth)
                .param("dateFrom", today.toString()).param("dateTo", today.minusDays(1).toString()))).isEqualTo(400);
        assertThat(status(get(BASE + "/items/" + placementId + "/data").header("Authorization", auth)
                .param("dateFrom", today.toString()).param("dateTo", today.plusYears(5).plusDays(1).toString())))
                .isEqualTo(400);

        assertThat(status(delete(BASE + "/items/" + placementId).header("Authorization", auth))).isEqualTo(204);
        assertThat(status(get(BASE + "/items/" + placementId + "/data").header("Authorization", auth)))
                .isEqualTo(404);
    }

    @Test
    @DisplayName("manager without project membership gets an empty, project-scoped result")
    void managerIsProjectScoped() throws Exception {
        String auth = bearer(managerId, managerRole);
        String library = body(get(BASE + "/library").header("Authorization", auth), 200);
        List<Boolean> available = JsonPath.read(library, "$[?(@.id == " + widgetId + ")].available");
        assertThat(available).containsExactly(true);

        long placementId = addPlacement(auth, 201);
        String data = body(get(BASE + "/items/" + placementId + "/data").header("Authorization", auth), 200);
        assertThat((List<?>) JsonPath.read(data, "$.rows")).isEmpty();
    }

    @Test
    @DisplayName("foreman without PROJECTS sees the widget disabled, cannot add it, cannot read foreign tiles")
    void foremanWithoutAccess() throws Exception {
        String auth = bearer(foremanId, foremanRole);
        String library = body(get(BASE + "/library").header("Authorization", auth), 200);
        String w = "$[?(@.id == " + widgetId + ")]";
        assertThat((List<Boolean>) JsonPath.read(library, w + ".available")).containsExactly(false);
        assertThat((List<String>) JsonPath.read(library, w + ".unavailableReason")).containsExactly("NO_ACCESS");
        assertThat((List<String>) JsonPath.read(library, w + ".missingResources[*]")).containsExactly("PROJECTS");

        assertThat(status(post(BASE + "/items").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"widgetId\":" + widgetId + "}"))).isEqualTo(403);

        long adminPlacement = addPlacement(bearer(adminId, adminRole), 201);
        String err = body(get(BASE + "/items/" + adminPlacement + "/data").header("Authorization", auth), 403);
        assertThat(JsonPath.<String>read(err, "$.message")).isNotBlank();
    }

    @Test
    @DisplayName("losing access flips the placement to NO_ACCESS and the fetch to 403 with a reason")
    void accessLossFlipsStateAndFetch() throws Exception {
        String auth = bearer(managerId, managerRole);
        long placementId = addPlacement(auth, 201);
        tx().executeWithoutResult(s -> em.createQuery(
                "delete from RoleResourceEntity rr where rr.role.id in (select r.id from RoleEntity r where r.code = :c)")
                .setParameter("c", "MANAGER").executeUpdate());
        permissionCache.invalidate("MANAGER");

        String layout = body(get(BASE).header("Authorization", auth), 200);
        assertThat((List<String>) JsonPath.read(layout, "$.items[?(@.id == " + placementId + ")].state"))
                .containsExactly("NO_ACCESS");
        String err = body(get(BASE + "/items/" + placementId + "/data").header("Authorization", auth), 403);
        assertThat(JsonPath.<String>read(err, "$.params.reason")).isEqualTo("NO_ACCESS");
    }

    @Test
    @DisplayName("a placement of another user is 403 for data fetch and delete, even with full access (task 7.1)")
    void foreignPlacementIs403() throws Exception {
        long adminPlacement = addPlacement(bearer(adminId, adminRole), 201);
        String manager = bearer(managerId, managerRole);
        String err = body(get(BASE + "/items/" + adminPlacement + "/data").header("Authorization", manager), 403);
        assertThat(JsonPath.<String>read(err, "$.message")).isNotBlank();
        assertThat(status(delete(BASE + "/items/" + adminPlacement).header("Authorization", manager))).isEqualTo(403);
        // the owner's placement is untouched
        assertThat(status(get(BASE + "/items/" + adminPlacement + "/data")
                .header("Authorization", bearer(adminId, adminRole)))).isEqualTo(200);
    }

    @Test
    @DisplayName("layout state and data fetch agree through revoke and re-grant of the matrix (task 7.1)")
    void layoutAndFetchAgreeAcrossMatrixChanges() throws Exception {
        String auth = bearer(managerId, managerRole);
        long placementId = addPlacement(auth, 201);
        String stateExpr = "$.items[?(@.id == " + placementId + ")].state";

        assertThat((List<String>) JsonPath.read(body(get(BASE).header("Authorization", auth), 200), stateExpr))
                .containsExactly("AVAILABLE");
        assertThat(status(get(BASE + "/items/" + placementId + "/data").header("Authorization", auth))).isEqualTo(200);

        tx().executeWithoutResult(s -> em.createQuery(
                "delete from RoleResourceEntity rr where rr.role.id in (select r.id from RoleEntity r where r.code = :c)")
                .setParameter("c", "MANAGER").executeUpdate());
        permissionCache.invalidate("MANAGER");
        assertThat((List<String>) JsonPath.read(body(get(BASE).header("Authorization", auth), 200), stateExpr))
                .containsExactly("NO_ACCESS");
        assertThat(status(get(BASE + "/items/" + placementId + "/data").header("Authorization", auth))).isEqualTo(403);

        ensureRole("MANAGER", true);
        assertThat((List<String>) JsonPath.read(body(get(BASE).header("Authorization", auth), 200), stateExpr))
                .containsExactly("AVAILABLE");
        assertThat(status(get(BASE + "/items/" + placementId + "/data").header("Authorization", auth))).isEqualTo(200);
    }

    @Test
    void projectPickerSearchesAccessibleProjects() throws Exception {
        String admin = body(get(BASE + "/projects").header("Authorization", bearer(adminId, adminRole))
                .param("q", runId.toLowerCase()), 200);
        assertThat((List<Integer>) JsonPath.read(admin, "$[*].id")).containsExactly((int) projectId);
        String manager = body(get(BASE + "/projects").header("Authorization", bearer(managerId, managerRole))
                .param("q", runId), 200);
        assertThat((List<?>) JsonPath.read(manager, "$")).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private long addPlacement(String auth, int expected) throws Exception {
        String created = body(post(BASE + "/items").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"widgetId\":" + widgetId + "}"), expected);
        return ((Number) JsonPath.read(created, "$.id")).longValue();
    }

    private int status(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private String body(RequestBuilder request, int expectedStatus) throws Exception {
        MvcResult r = mockMvc.perform(request).andReturn();
        String content = r.getResponse().getContentAsString();
        assertThat(r.getResponse().getStatus()).as(content).isEqualTo(expectedStatus);
        return content;
    }

    private String bearer(long userId, RoleEntity role) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(
                userId, role.getCode(), "dash" + userId + "+" + runId + "@example.com");
    }

    /** Find-or-create a role; MANAGER-like roles get PROJECTS READ. */
    private RoleEntity ensureRole(String code, boolean projectsRead) {
        RoleEntity role = tx().execute(s -> {
            List<RoleEntity> existing = em.createQuery("select r from RoleEntity r where r.code = :c", RoleEntity.class)
                    .setParameter("c", code).getResultList();
            RoleEntity r;
            if (existing.isEmpty()) {
                r = new RoleEntity();
                r.setCode(code);
                r.setNameRU("Роль " + code);
                r.setNamePL("Rola " + code);
                r.setSystem(true);
                em.persist(r);
            } else {
                r = existing.get(0);
            }
            boolean granted = r.getRoleResources().stream()
                    .anyMatch(x -> "PROJECTS".equals(x.getResource().getCode()));
            if (projectsRead && !granted) {
                RoleResourceEntity rr = new RoleResourceEntity();
                rr.setRole(r);
                rr.setResource(resource("PROJECTS"));
                rr.setOperations(new ArrayList<>(List.of(operation("READ"))));
                r.getRoleResources().add(rr);
            }
            em.flush();
            return r;
        });
        permissionCache.invalidate(code);
        return role;
    }

    private ResourceEntity resource(String code) {
        List<ResourceEntity> existing = em.createQuery("select r from ResourceEntity r where r.code = :c",
                ResourceEntity.class).setParameter("c", code).getResultList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        ResourceEntity r = new ResourceEntity();
        r.setCode(code);
        r.setNameRU(code);
        r.setNamePL(code);
        em.persist(r);
        return r;
    }

    private OperationEntity operation(String code) {
        List<OperationEntity> existing = em.createQuery("select o from OperationEntity o where o.code = :c",
                OperationEntity.class).setParameter("c", code).getResultList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        OperationEntity o = new OperationEntity();
        o.setCode(code);
        o.setNameRU(code);
        o.setNamePL(code);
        em.persist(o);
        return o;
    }

    private long persistUser(String key, RoleEntity role) {
        return tx().execute(s -> {
            UserEntity u = new UserEntity();
            u.setName(key + " " + runId);
            u.setEmail(key + "+" + runId + "@example.com");
            u.setRole(em.getReference(RoleEntity.class, role.getId()));
            u.setActive(true);
            u.setStatus(UserStatus.ACTIVE);
            u.setLocale(java.util.Locale.forLanguageTag("pl"));
            em.persist(u);
            em.flush();
            return u.getId();
        });
    }
}
