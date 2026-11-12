package com.foremen.integration;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.model.DashboardWidgetEntity;
import com.foremen.dao.model.RoleEntity;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * FOR-10 task 11.7 — move / swap / add-with-cell over the real MVC + security stack against
 * Testcontainers PostgreSQL. Covers happy paths, identical 403 for foreign vs missing ids, swap
 * rollback, race outcomes serialised by the per-user advisory lock, and add without first-fit fallback.
 *
 * <p>Widgets: W1, W2 are 2x2; W3, W4, W5 are 1x1. Users are ADMIN (matrix bypass). Repeatability: every
 * row carries a per-run id and {@link #cleanUp()} removes placements, state, widgets and users.
 *
 * <p><b>Validates: Requirements 19.7, 19.8, 19.10, 19.13, 19.14, 19.18, 21.8, 21.9</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("integration-test")
class DashboardMoveSwapIntegrationTest {

    private static final String BASE = "/api/dashboard";

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
    private MessageResolver messageResolver;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @PersistenceContext
    private EntityManager em;

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    private RoleEntity adminRole;
    private long userId;
    private long otherUserId;
    private final List<Long> widgetIds = new ArrayList<>();
    private String auth;
    private String otherAuth;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void setUp() {
        adminRole = ensureAdminRole();
        userId = persistUser("a");
        otherUserId = persistUser("b");
        auth = bearer(userId);
        otherAuth = bearer(otherUserId);
        widgetIds.clear();
        int[][] spans = {{2, 2}, {2, 2}, {1, 1}, {1, 1}, {1, 1}};
        for (int i = 0; i < spans.length; i++) {
            widgetIds.add(persistWidget(i + 1, spans[i][0], spans[i][1]));
        }
    }

    @AfterEach
    void cleanUp() {
        tx().executeWithoutResult(s -> {
            List<Long> users = List.of(userId, otherUserId);
            em.createQuery("delete from UserDashboardWidgetEntity p where p.user.id in :u")
                    .setParameter("u", users).executeUpdate();
            em.createQuery("delete from UserDashboardStateEntity st where st.userId in :u")
                    .setParameter("u", users).executeUpdate();
            em.createQuery("delete from DashboardWidgetEntity w where w.id in :ids")
                    .setParameter("ids", widgetIds).executeUpdate();
            em.createQuery("delete from UserEntity u where u.id in :u").setParameter("u", users).executeUpdate();
        });
    }

    // ------------------------------------------------------------------ move

    @Test
    @DisplayName("move happy path changes only the cell; idempotent; INACTIVE placement stays movable (19.7, 19.10)")
    void moveHappyPath() throws Exception {
        long p1 = add(0, 0, 201);
        String moved = body(move(auth, p1, 8), 200);
        assertThat(JsonPath.<Integer>read(moved, "$.cellIndex")).isEqualTo(8);
        assertThat(((Number) JsonPath.read(moved, "$.widgetId")).longValue()).isEqualTo(widgetIds.get(0));
        assertThat(cellOf(p1)).isEqualTo(8);

        // same cell again: still 200 and unchanged
        assertThat(status(move(auth, p1, 8))).isEqualTo(200);
        assertThat(cellOf(p1)).isEqualTo(8);

        // deactivate the widget: the placement is INACTIVE but still movable
        tx().executeWithoutResult(s -> em.createQuery("update DashboardWidgetEntity w set w.active = false "
                + "where w.id = :id").setParameter("id", widgetIds.get(0)).executeUpdate());
        String inactive = body(move(auth, p1, 16), 200);
        assertThat(JsonPath.<String>read(inactive, "$.state")).isEqualTo("INACTIVE");
        assertThat(cellOf(p1)).isEqualTo(16);
    }

    @Test
    @DisplayName("move to an invalid or occupied cell is 400 / 409 and leaves the layout unchanged (19.8)")
    void moveRejections() throws Exception {
        long big = add(0, 0, 201);
        long small = add(2, 8, 201);
        assertThat(status(move(auth, big, 3))).isEqualTo(400);   // 2x2 at col 3 leaves the grid
        assertThat(status(move(auth, big, 36))).isEqualTo(400);
        assertThat(status(move(auth, big, -1))).isEqualTo(400);
        assertThat(status(move(auth, small, 1))).isEqualTo(409); // inside the big tile
        assertThat(cellOf(big)).isEqualTo(0);
        assertThat(cellOf(small)).isEqualTo(8);
    }

    @Test
    @DisplayName("foreign and missing placement ids give the identical 403 for move and swap (19.8)")
    void foreignAndMissingAre403() throws Exception {
        long mine = add(2, 0, 201);
        long mine2 = add(3, 1, 201);

        String foreignMove = body(move(otherAuth, mine, 5), 403);
        String missingMove = body(move(otherAuth, 987654321L, 5), 403);
        assertThat(JsonPath.<String>read(foreignMove, "$.message"))
                .isEqualTo(JsonPath.<String>read(missingMove, "$.message"));

        String foreignSwap = body(swap(otherAuth, mine, mine2), 403);
        String missingSwap = body(swap(otherAuth, mine, 987654321L), 403);
        String bothMissing = body(swap(otherAuth, 987654320L, 987654321L), 403);
        String msg = JsonPath.read(foreignMove, "$.message");
        assertThat(JsonPath.<String>read(foreignSwap, "$.message")).isEqualTo(msg);
        assertThat(JsonPath.<String>read(missingSwap, "$.message")).isEqualTo(msg);
        assertThat(JsonPath.<String>read(bothMissing, "$.message")).isEqualTo(msg);

        // owner sees nothing changed
        assertThat(cellOf(mine)).isEqualTo(0);
        assertThat(cellOf(mine2)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ swap

    @Test
    @DisplayName("swap happy path exchanges both cells in one call (19.13)")
    void swapHappyPath() throws Exception {
        long big = add(0, 0, 201);
        long small = add(2, 8, 201);
        String res = body(swap(auth, big, small), 200);
        assertThat(JsonPath.<List<Integer>>read(res, "$.items[*].id")).hasSize(2);
        assertThat(cellOf(big)).isEqualTo(8);
        assertThat(cellOf(small)).isEqualTo(0);
    }

    @Test
    @DisplayName("a failing swap (400 or 409) rolls back: neither placement moves (19.13, 19.14)")
    void swapRollback() throws Exception {
        long big = add(0, 0, 201);
        long a = add(2, 6, 201);
        long blocker = add(3, 10, 201);
        // big -> 6 would cover 6,7,10,11 where the blocker stands
        assertThat(status(swap(auth, big, a))).isEqualTo(409);
        assertThat(cellOf(big)).isEqualTo(0);
        assertThat(cellOf(a)).isEqualTo(6);
        assertThat(cellOf(blocker)).isEqualTo(10);

        long edge = add(4, 3, 201);
        // big -> 3 leaves the grid
        assertThat(status(swap(auth, big, edge))).isEqualTo(400);
        assertThat(cellOf(big)).isEqualTo(0);
        assertThat(cellOf(edge)).isEqualTo(3);

        assertThat(status(swap(auth, big, big))).isEqualTo(400);
        assertThat(status(swap(auth, big, null))).isEqualTo(400);
    }

    // ------------------------------------------------------------------ races

    @Test
    @DisplayName("two concurrent moves into the same cell: exactly one 200 and one 409 cell.occupied (19.18)")
    void concurrentMovesSameCell() throws Exception {
        long p3 = add(2, 0, 201);
        long p4 = add(3, 1, 201);
        List<Integer> codes = race(
                () -> status(move(auth, p3, 20)),
                () -> status(move(auth, p4, 20)));
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(List.of(cellOf(p3), cellOf(p4))).containsAnyOf(20).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("concurrent swap and move into the same cells: exactly one success and one 409 (19.18)")
    void concurrentSwapAndMove() throws Exception {
        long p3 = add(2, 8, 201);
        long p4 = add(3, 9, 201);
        long p5 = add(4, 20, 201);
        List<Integer> codes = race(
                () -> status(swap(auth, p3, p4)),
                () -> status(move(auth, p5, 8)));
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(List.of(cellOf(p3), cellOf(p4), cellOf(p5))).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("two concurrent adds into the same cell: exactly one 201 and one 409 cell.occupied (19.18)")
    void concurrentAddsSameCell() throws Exception {
        List<Integer> codes = race(
                () -> status(addRequest(auth, 2, 30)),
                () -> status(addRequest(auth, 3, 30)));
        assertThat(codes).containsExactlyInAnyOrder(201, 409);
        String err = body(addRequest(auth, 4, 30).locale(Locale.forLanguageTag("pl")), 409);
        assertThat(JsonPath.<String>read(err, "$.message")).isEqualTo(
                messageResolver.resolve("error.dashboard.cell.occupied", new Object[0], Locale.forLanguageTag("pl")));
        assertThat(layoutIds()).hasSize(1);
    }

    // ------------------------------------------------------------------ add without fallback

    @Test
    @DisplayName("add with an out-of-grid or occupied cellIndex is 400 / 409 and never falls back (21.8, 21.9)")
    void addWithoutFallback() throws Exception {
        long first = add(0, 0, 201);
        assertThat(status(addRequest(auth, 2, 36))).isEqualTo(400);
        assertThat(status(addRequest(auth, 2, -1))).isEqualTo(400);
        assertThat(status(addRequest(auth, 1, 3))).isEqualTo(400);   // 2x2 does not fit at col 3
        assertThat(status(addRequest(auth, 2, 5))).isEqualTo(409);   // inside the first tile
        assertThat(layoutIds()).containsExactly(first);

        // without a cellIndex first-fit is still used
        String ok = body(addRequest(auth, 2, null), 201);
        assertThat(JsonPath.<Integer>read(ok, "$.cellIndex")).isEqualTo(2);
    }

    // ------------------------------------------------------------------ helpers

    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> c : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return c.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private long add(int widgetIndex, int cell, int expected) throws Exception {
        String created = body(addRequest(auth, widgetIndex, cell), expected);
        return ((Number) JsonPath.read(created, "$.id")).longValue();
    }

    private MockHttpServletRequestBuilder addRequest(String token, int widgetIndex, Integer cell) {
        String json = "{\"widgetId\":" + widgetIds.get(widgetIndex)
                + (cell == null ? "" : ",\"cellIndex\":" + cell) + "}";
        return post(BASE + "/items").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private RequestBuilder move(String token, long id, int cell) {
        return put(BASE + "/items/" + id + "/move").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"cellIndex\":" + cell + "}");
    }

    private RequestBuilder swap(String token, long first, Long second) {
        String json = "{\"firstId\":" + first + ",\"secondId\":" + (second == null ? "null" : second) + "}";
        return post(BASE + "/items/swap").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private int cellOf(long placementId) throws Exception {
        String layout = body(get(BASE).header("Authorization", auth), 200);
        List<Integer> cells = JsonPath.read(layout, "$.items[?(@.id == " + placementId + ")].cellIndex");
        assertThat(cells).hasSize(1);
        return cells.get(0);
    }

    private List<Long> layoutIds() throws Exception {
        String layout = body(get(BASE).header("Authorization", auth), 200);
        List<Number> ids = JsonPath.read(layout, "$.items[*].id");
        return ids.stream().map(Number::longValue).toList();
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

    private String bearer(long id) {
        return "Bearer " + jwtTokenProvider.generateAccessToken(
                id, adminRole.getCode(), "dash" + id + "+" + runId + "@example.com");
    }

    private RoleEntity ensureAdminRole() {
        RoleEntity role = tx().execute(s -> {
            List<RoleEntity> existing = em.createQuery("select r from RoleEntity r where r.code = 'ADMIN'",
                    RoleEntity.class).getResultList();
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            RoleEntity r = new RoleEntity();
            r.setCode("ADMIN");
            r.setNameRU("Роль ADMIN");
            r.setNamePL("Rola ADMIN");
            r.setSystem(true);
            em.persist(r);
            em.flush();
            return r;
        });
        permissionCache.invalidate("ADMIN");
        return role;
    }

    private long persistUser(String key) {
        return tx().execute(s -> {
            UserEntity u = new UserEntity();
            u.setName(key + " " + runId);
            u.setEmail(key + "+" + runId + "@example.com");
            u.setRole(em.getReference(RoleEntity.class, adminRole.getId()));
            u.setActive(true);
            u.setStatus(UserStatus.ACTIVE);
            u.setLocale(Locale.forLanguageTag("pl"));
            em.persist(u);
            em.flush();
            return u.getId();
        });
    }

    private long persistWidget(int n, int cols, int rows) {
        return tx().execute(s -> {
            DashboardWidgetEntity w = new DashboardWidgetEntity();
            w.setCode("MS" + n + "_" + runId);
            w.setNameRu("Виджет " + n + " " + runId);
            w.setNamePl("Widżet " + n + " " + runId);
            w.setConfig(Map.of(
                    "schemaVersion", 1,
                    "dataset", "PROJECTS",
                    "visualization", "TABLE",
                    "columns", List.of(Map.of("field", "name",
                            "label", Map.of("ru", "Проект", "pl", "Projekt")))));
            w.setConfigVersion(1);
            w.setColSpan((short) cols);
            w.setRowSpan((short) rows);
            w.setProjectScope("NONE");
            w.setActive(true);
            w.setSystemSeed(false);
            em.persist(w);
            em.flush();
            return w.getId();
        });
    }
}
