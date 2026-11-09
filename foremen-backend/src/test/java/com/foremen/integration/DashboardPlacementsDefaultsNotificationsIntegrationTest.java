package com.foremen.integration;

import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.DashboardWidgetEntity;
import com.foremen.dao.model.NotificationEntity;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleDefaultWidgetEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.dao.model.UserDashboardWidgetEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.dao.model.WorkerKind;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.AdminBootstrap;
import com.foremen.service.UserService;
import com.foremen.service.dashboard.DashboardDefaultsService;
import com.foremen.service.dashboard.DashboardLayoutService;
import com.foremen.service.dashboard.DashboardWidgetService;
import com.foremen.service.dashboard.PlacementView;
import com.foremen.service.mail.InvitationMailSender;
import com.foremen.service.model.UserServiceExtendedModel;
import com.foremen.service.permission.PermissionCache;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FOR-10 task 7.2 — placement rules, role-default seeding and deactivation notifications against real
 * Testcontainers PostgreSQL (advisory locks, AFTER_COMMIT listeners and the real services).
 *
 * <p>Repeatability: every row carries a per-run id and is removed in {@link #cleanUp()}; each test is
 * independent of the others. ADMIN users exist only in the tests that need them (the bootstrap test
 * requires that no ADMIN user exists).
 *
 * <p><b>Validates: Requirements 3.3, 3.4, 3.5, 3.6, 3.7, 12.3, 15.2, 15.3, 15.4, 17.2</b>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
class DashboardPlacementsDefaultsNotificationsIntegrationTest {

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

    /** Keeps user creation off SMTP. */
    @MockitoBean
    private InvitationMailSender invitationMailSender;

    @Autowired
    private DashboardLayoutService layoutService;
    @Autowired
    private DashboardDefaultsService defaultsService;
    @Autowired
    private DashboardWidgetService widgetService;
    @Autowired
    private UserService userService;
    @Autowired
    private UserDao userDao;
    @Autowired
    private RoleDao roleDao;
    @Autowired
    private PermissionCache permissionCache;
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @PersistenceContext
    private EntityManager em;

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    private final List<Long> userIds = new CopyOnWriteArrayList<>();
    private final List<Long> widgetIds = new ArrayList<>();
    private final List<Long> projectIds = new ArrayList<>();

    private String roleCode;
    private RoleEntity role;
    private long widgetA;
    private long widgetB;
    private long widgetProject;
    private long widgetInactive;
    private long project1;
    private long project2;
    private int emailSeq;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void setUp() {
        roleCode = "DR" + runId;
        role = createRoleWithProjectsRead(roleCode);
        widgetA = persistWidget("A", "NONE", true);
        widgetB = persistWidget("B", "NONE", true);
        widgetProject = persistWidget("P", "SINGLE_PROJECT", true);
        widgetInactive = persistWidget("I", "NONE", false);
        project1 = persistProject("1");
        project2 = persistProject("2");
    }

    @AfterEach
    void cleanUp() {
        tx().executeWithoutResult(s -> {
            if (!userIds.isEmpty()) {
                em.createQuery("delete from NotificationEntity n where n.recipient.id in :u")
                        .setParameter("u", userIds).executeUpdate();
                em.createQuery("delete from UserDashboardWidgetEntity p where p.user.id in :u")
                        .setParameter("u", userIds).executeUpdate();
                em.createQuery("delete from UserDashboardStateEntity st where st.userId in :u")
                        .setParameter("u", userIds).executeUpdate();
                em.createQuery("delete from InviteTokenEntity t where t.user.id in :u")
                        .setParameter("u", userIds).executeUpdate();
            }
            em.createQuery("delete from RoleDefaultWidgetEntity d where d.widget.id in :w")
                    .setParameter("w", widgetIds).executeUpdate();
            em.createQuery("delete from UserDashboardWidgetEntity p where p.widget.id in :w")
                    .setParameter("w", widgetIds).executeUpdate();
            em.createQuery("delete from AuditLogEntity a where a.entityClass = 'DashboardWidgetEntity' "
                    + "and a.entityId in :w").setParameter("w", widgetIds).executeUpdate();
            em.createQuery("delete from DashboardWidgetEntity w where w.id in :w")
                    .setParameter("w", widgetIds).executeUpdate();
            em.createQuery("delete from ProjectEntity p where p.id in :p")
                    .setParameter("p", projectIds).executeUpdate();
            if (!userIds.isEmpty()) {
                em.createQuery("delete from AuditLogEntity a where a.entityClass like '%User%' "
                        + "and a.entityId in :u").setParameter("u", userIds).executeUpdate();
                em.createQuery("delete from UserEntity u where u.id in :u")
                        .setParameter("u", userIds).executeUpdate();
            }
            em.createQuery("delete from RoleResourceEntity rr where rr.role.id in (select r.id from RoleEntity r where r.code = :c)")
                    .setParameter("c", roleCode).executeUpdate();
            em.createQuery("delete from RoleEntity r where r.code = :c")
                    .setParameter("c", roleCode).executeUpdate();
        });
        permissionCache.invalidate(roleCode);
    }

    // ------------------------------------------------------------------ concurrency (R12.3)

    @Test
    @DisplayName("two threads adding widgets onto the same cell: advisory lock lets exactly one succeed")
    void concurrentAddsOverlappingCell() throws Exception {
        long user = persistUser("conc", role);
        markInitialized(user);
        for (int round = 0; round < 3; round++) {
            List<Object> outcomes = race(
                    () -> layoutService.addPlacement(user, widgetA, 0, null),
                    () -> layoutService.addPlacement(user, widgetB, 1, null));
            assertThat(outcomes.stream().filter(PlacementView.class::isInstance)).hasSize(1);
            Object failure = outcomes.stream().filter(o -> !(o instanceof PlacementView)).findFirst().orElseThrow();
            assertThat(failure).isInstanceOf(ForemenApiException.class);
            assertThat(((ForemenApiException) failure).getMessageCode()).isEqualTo("error.dashboard.cell.occupied");
            assertThat(placementsOf(user)).hasSize(1);
            clearPlacements(user);
        }
    }

    @Test
    @DisplayName("two threads adding the same non-project widget: exactly one succeeds, the other is a 409 duplicate")
    void concurrentAddsSameWidget() throws Exception {
        long user = persistUser("dup", role);
        markInitialized(user);
        List<Object> outcomes = race(
                () -> layoutService.addPlacement(user, widgetA, null, null),
                () -> layoutService.addPlacement(user, widgetA, null, null));
        assertThat(outcomes.stream().filter(PlacementView.class::isInstance)).hasSize(1);
        Object failure = outcomes.stream().filter(o -> !(o instanceof PlacementView)).findFirst().orElseThrow();
        assertThat(((ForemenApiException) failure).getMessageCode()).isEqualTo("error.dashboard.widget.duplicate");
        assertThat(placementsOf(user)).hasSize(1);
    }

    // ------------------------------------------------------------------ overlap / duplicate rules (R3.3-3.6)

    @Test
    @DisplayName("overlap, out-of-grid and duplicate rules for non-project widgets")
    void overlapAndDuplicateRules() {
        long user = persistUser("rules", role);
        markInitialized(user);
        PlacementView first = layoutService.addPlacement(user, widgetA, 0, null);
        assertThat(first.cellIndex()).isEqualTo((short) 0);
        // footprint of a 2x2 widget at cell 0 covers cells 0,1,4,5
        assertThat(codeOf(() -> layoutService.addPlacement(user, widgetB, 1, null)))
                .isEqualTo("error.dashboard.cell.occupied");
        assertThat(codeOf(() -> layoutService.addPlacement(user, widgetB, 3, null)))
                .isEqualTo("error.dashboard.cell.invalid");
        assertThat(codeOf(() -> layoutService.addPlacement(user, widgetA, 2, null)))
                .isEqualTo("error.dashboard.widget.duplicate");
        assertThat(codeOf(() -> layoutService.addPlacement(user, widgetInactive, null, null)))
                .isEqualTo("error.dashboard.widget.inactive");
        PlacementView second = layoutService.addPlacement(user, widgetB, 2, null);
        assertThat(second.cellIndex()).isEqualTo((short) 2);
        // first-fit skips the occupied footprints and nothing is left in a half-written state
        assertThat(placementsOf(user)).hasSize(2);
        // removing frees the footprint again
        layoutService.removePlacement(user, first.id());
        assertThat(layoutService.addPlacement(user, widgetA, 0, null).cellIndex()).isEqualTo((short) 0);
    }

    @Test
    @DisplayName("project widget: copies for different projects allowed, same project is a 409 duplicate")
    void projectWidgetCopies() {
        long admin = persistAdmin();
        markInitialized(admin);
        PlacementView p1 = layoutService.addPlacement(admin, widgetProject, null, project1);
        PlacementView p2 = layoutService.addPlacement(admin, widgetProject, null, project2);
        assertThat(p1.id()).isNotEqualTo(p2.id());
        assertThat(p1.projectId()).isEqualTo(project1);
        assertThat(p2.projectId()).isEqualTo(project2);
        assertThat(p1.cellIndex()).isNotEqualTo(p2.cellIndex());
        assertThat(codeOf(() -> layoutService.addPlacement(admin, widgetProject, null, project1)))
                .isEqualTo("error.dashboard.widget.duplicate");
        assertThat(codeOf(() -> layoutService.addPlacement(admin, widgetProject, null, null)))
                .isEqualTo("error.dashboard.project.required");
        assertThat(codeOf(() -> layoutService.addPlacement(admin, widgetA, null, project1)))
                .isEqualTo("error.dashboard.project.unexpected");
        assertThat(placementsOf(admin)).hasSize(2);
    }

    // ------------------------------------------------------------------ deactivation fan-out (R17.2)

    @Test
    @DisplayName("deactivation notifies each distinct user once through the real AFTER_COMMIT listener")
    void deactivationFanOut() {
        long admin = persistAdmin();
        long userA = persistUser("fanA", role);
        long userB = persistUser("fanB", role);
        long userC = persistUser("fanC", role);
        // admin and userA hold two copies (different projects), userB one, userC none
        insertPlacement(admin, widgetProject, 0, project1);
        insertPlacement(admin, widgetProject, 2, project2);
        insertPlacement(userA, widgetProject, 0, project1);
        insertPlacement(userA, widgetProject, 2, project2);
        insertPlacement(userB, widgetProject, 0, project1);
        insertPlacement(userC, widgetA, 0, null);

        widgetService.deactivate(widgetProject);

        for (long u : List.of(admin, userA, userB)) {
            List<NotificationEntity> list = notificationsOf(u);
            assertThat(list).as("user %d", u).hasSize(1);
            assertThat(list.get(0).getType()).isEqualTo("notification.dashboard.widget.deactivated");
            assertThat(list.get(0).getBody()).isEqualTo("Widget P " + runId);
            assertThat(list.get(0).getDeepLink()).isEqualTo("/");
        }
        assertThat(notificationsOf(userC)).isEmpty();

        // deactivating an already inactive widget publishes nothing
        widgetService.deactivate(widgetProject);
        assertThat(notificationsOf(userA)).hasSize(1);
    }

    // ------------------------------------------------------------------ seeding (R15.2-15.4)

    @Test
    @DisplayName("UserService.create seeds role defaults once (inactive/project/overlapping defaults skipped)")
    void seedingOnUserServiceCreate() {
        configureRoleDefaults(role.getId());
        String email = email("svc");
        UserServiceExtendedModel created = tx().execute(s -> userService.create(new UserServiceExtendedModel(
                null, "Svc " + runId, email, null, role.getId(), null, true, "pl",
                null, null, null, null, null, null)));
        long id = created.id();
        userIds.add(id);

        assertSeededDefaults(id);
        assertThat(layoutService.read(id)).hasSize(2);
        assertSeededDefaults(id);
    }

    @Test
    @DisplayName("UserService.createWorkerRecord seeds role defaults")
    void seedingOnCreateWorkerRecord() {
        configureRoleDefaults(role.getId());
        UserEntity worker = tx().execute(s -> userService.createWorkerRecord("Worker " + runId,
                email("wrk"), null, em.find(RoleEntity.class, role.getId()), WorkerKind.PERSON, null, null));
        userIds.add(worker.getId());

        assertSeededDefaults(worker.getId());
    }

    @Test
    @DisplayName("AdminBootstrap creates the ADMIN user and seeds the ADMIN role defaults")
    void seedingOnAdminBootstrap() {
        RoleEntity admin = ensureAdminRole();
        assertThat(userDao.countByRoleCode("ADMIN")).as("no ADMIN user may exist").isZero();
        tx().executeWithoutResult(s -> {
            RoleDefaultWidgetEntity d = new RoleDefaultWidgetEntity();
            d.setRoleId(admin.getId());
            d.setCellIndex((short) 0);
            d.setWidget(em.getReference(DashboardWidgetEntity.class, widgetA));
            em.persist(d);
        });
        String email = email("boot");
        AdminBootstrap bootstrap = new AdminBootstrap(userDao, roleDao, new BCryptPasswordEncoder(4));
        ReflectionTestUtils.setField(bootstrap, "eventPublisher", eventPublisher);
        ReflectionTestUtils.setField(bootstrap, "adminEmail", email);
        ReflectionTestUtils.setField(bootstrap, "adminPassword", "Secret-" + runId);

        tx().executeWithoutResult(s -> bootstrap.run(null));

        UserEntity created = userDao.findByEmailIgnoreCase(email).orElseThrow();
        userIds.add(created.getId());
        List<UserDashboardWidgetEntity> placements = placementsOf(created.getId());
        assertThat(placements).hasSize(1);
        assertThat(placements.get(0).getWidget().getId()).isEqualTo(widgetA);
        assertThat(stateCount(created.getId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("lazy seeding on first layout read, idempotent, and a concurrent seed yields one copy")
    void lazySeedingAndIdempotency() throws Exception {
        configureRoleDefaults(role.getId());
        long lazy = persistUser("lazy", role);
        assertThat(placementsOf(lazy)).isEmpty();
        assertThat(stateCount(lazy)).isZero();

        assertThat(layoutService.read(lazy)).hasSize(2);
        assertSeededDefaults(lazy);

        // a user-removed default is not re-created by later reads or explicit re-seeding
        Long removed = placementsOf(lazy).get(0).getId();
        layoutService.removePlacement(lazy, removed);
        defaultsService.seedFor(lazy);
        assertThat(layoutService.read(lazy)).hasSize(1);
        assertThat(stateCount(lazy)).isEqualTo(1L);

        // two concurrent seeds for a new user: the advisory lock admits exactly one seeding
        long raced = persistUser("race", role);
        race(() -> {
            defaultsService.seedFor(raced);
            return "ok";
        }, () -> {
            defaultsService.seedFor(raced);
            return "ok";
        });
        assertThat(placementsOf(raced)).hasSize(2);
        assertThat(stateCount(raced)).isEqualTo(1L);
    }

    // ------------------------------------------------------------------ helpers

    private void assertSeededDefaults(long userId) {
        List<UserDashboardWidgetEntity> placements = placementsOf(userId);
        assertThat(placements.stream().map(p -> p.getWidget().getId()).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(widgetA, widgetB);
        assertThat(placements.stream().map(p -> (int) p.getCellIndex()).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(0, 2);
        assertThat(stateCount(userId)).isEqualTo(1L);
    }

    /** Role defaults: A@0, B@2 valid; inactive@8, project@10 skipped; a second widget overlapping A@1 skipped. */
    private void configureRoleDefaults(long roleId) {
        tx().executeWithoutResult(s -> {
            addDefault(roleId, 0, widgetA);
            addDefault(roleId, 2, widgetB);
            addDefault(roleId, 8, widgetInactive);
            addDefault(roleId, 10, widgetProject);
            addDefault(roleId, 1, widgetB);
        });
    }

    private void addDefault(long roleId, int cell, long widget) {
        RoleDefaultWidgetEntity d = new RoleDefaultWidgetEntity();
        d.setRoleId(roleId);
        d.setCellIndex((short) cell);
        d.setWidget(em.getReference(DashboardWidgetEntity.class, widget));
        em.persist(d);
    }

    /** Runs both tasks at the same instant; returns each result or the thrown exception. */
    private List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private String codeOf(Runnable action) {
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(action::run);
        assertThat(t).isInstanceOf(ForemenApiException.class);
        return ((ForemenApiException) t).getMessageCode();
    }

    private List<UserDashboardWidgetEntity> placementsOf(long userId) {
        return tx().execute(s -> {
            List<UserDashboardWidgetEntity> list = em.createQuery(
                    "select p from UserDashboardWidgetEntity p where p.user.id = :u order by p.cellIndex",
                    UserDashboardWidgetEntity.class).setParameter("u", userId).getResultList();
            list.forEach(p -> p.getWidget().getId());
            return list;
        });
    }

    private long stateCount(long userId) {
        return tx().execute(s -> em.createQuery(
                "select count(st) from UserDashboardStateEntity st where st.userId = :u", Long.class)
                .setParameter("u", userId).getSingleResult());
    }

    private List<NotificationEntity> notificationsOf(long userId) {
        return tx().execute(s -> em.createQuery(
                "select n from NotificationEntity n where n.recipient.id = :u", NotificationEntity.class)
                .setParameter("u", userId).getResultList());
    }

    private void clearPlacements(long userId) {
        tx().executeWithoutResult(s -> em.createQuery("delete from UserDashboardWidgetEntity p where p.user.id = :u")
                .setParameter("u", userId).executeUpdate());
    }

    private void markInitialized(long userId) {
        tx().executeWithoutResult(s -> em.createNativeQuery(
                "insert into user_dashboard_state(user_id, initialized_at) values (:u, now())")
                .setParameter("u", userId).executeUpdate());
    }

    private void insertPlacement(long userId, long widget, int cell, Long project) {
        tx().executeWithoutResult(s -> {
            UserDashboardWidgetEntity p = new UserDashboardWidgetEntity();
            p.setUser(em.getReference(UserEntity.class, userId));
            p.setWidget(em.getReference(DashboardWidgetEntity.class, widget));
            p.setCellIndex((short) cell);
            if (project != null) {
                p.setProject(em.getReference(ProjectEntity.class, project));
            }
            em.persist(p);
        });
    }

    private String email(String key) {
        return key + (emailSeq++) + "+" + runId + "@example.com";
    }

    private long persistAdmin() {
        return persistUser("admin", ensureAdminRole());
    }

    private long persistUser(String key, RoleEntity userRole) {
        long id = tx().execute(s -> {
            UserEntity u = new UserEntity();
            u.setName(key + " " + runId);
            u.setEmail(email(key));
            u.setRole(em.getReference(RoleEntity.class, userRole.getId()));
            u.setActive(true);
            u.setStatus(UserStatus.ACTIVE);
            u.setLocale(java.util.Locale.forLanguageTag("pl"));
            em.persist(u);
            em.flush();
            return u.getId();
        });
        userIds.add(id);
        return id;
    }

    private long persistWidget(String key, String scope, boolean active) {
        long id = tx().execute(s -> {
            DashboardWidgetEntity w = new DashboardWidgetEntity();
            w.setCode("IT_" + key + "_" + runId);
            w.setNameRu("Widget " + key + " " + runId);
            w.setNamePl("Widget " + key + " " + runId);
            w.setConfig(Map.of("schemaVersion", 1, "dataset", "PROJECTS", "visualization", "TABLE"));
            w.setConfigVersion(1);
            w.setColSpan((short) 2);
            w.setRowSpan((short) 2);
            w.setProjectScope(scope);
            w.setActive(active);
            w.setSystemSeed(false);
            em.persist(w);
            em.flush();
            return w.getId();
        });
        widgetIds.add(id);
        return id;
    }

    private long persistProject(String key) {
        long id = tx().execute(s -> {
            ProjectEntity p = new ProjectEntity();
            p.setName("Dash " + key + " " + runId);
            p.setAddress("ul. Testowa " + runId);
            p.setStatus(ProjectStatus.ACTIVE);
            p.setStartDate(java.time.LocalDate.now());
            em.persist(p);
            em.flush();
            return p.getId();
        });
        projectIds.add(id);
        return id;
    }

    private RoleEntity ensureAdminRole() {
        return tx().execute(s -> {
            List<RoleEntity> existing = em.createQuery("select r from RoleEntity r where r.code = 'ADMIN'",
                    RoleEntity.class).getResultList();
            if (!existing.isEmpty()) {
                return existing.get(0);
            }
            RoleEntity r = new RoleEntity();
            r.setCode("ADMIN");
            r.setNameRU("Администратор");
            r.setNamePL("Administrator");
            r.setSystem(true);
            em.persist(r);
            em.flush();
            return r;
        });
    }

    private RoleEntity createRoleWithProjectsRead(String code) {
        RoleEntity created = tx().execute(s -> {
            RoleEntity r = new RoleEntity();
            r.setCode(code);
            r.setNameRU("Роль " + code);
            r.setNamePL("Rola " + code);
            r.setSystem(false);
            em.persist(r);
            RoleResourceEntity rr = new RoleResourceEntity();
            rr.setRole(r);
            rr.setResource(resource("PROJECTS"));
            rr.setOperations(new ArrayList<>(List.of(operation("READ"))));
            r.getRoleResources().add(rr);
            em.flush();
            return r;
        });
        permissionCache.invalidate(code);
        return created;
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
}
