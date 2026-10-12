package com.foremen.service.integration;

import com.foremen.controller.model.RefDto;
import com.foremen.dao.InviteTokenDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.model.InviteTokenEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.UserService;
import com.foremen.service.mail.InvitationMailSender;
import com.foremen.service.model.UserServiceExtendedModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Spring integration test for FOR-05-10b task 3.1 — User_Work_Categories on the generic user admin
 * create / edit flow ({@code UserService.create} / {@code update} / {@code findById}, Requirements
 * 1.1–1.5, 9.1).
 *
 * <p>Boot harness mirrors {@code InviteCreateAtomicityIntegrationTest}: {@code @SpringBootTest} +
 * {@code @Testcontainers} + {@code @ActiveProfiles("integration-test")}, where Liquibase is disabled
 * and Hibernate {@code create-drop} builds the schema from the mapped entities (so the
 * {@code user_work_categories} join table is built from the {@link UserEntity} {@code @ManyToMany}
 * mapping). {@link InviteTokenDao} and {@link InvitationMailSender} are mocked so the transactional
 * {@code afterCreate} invite hook sends no real email and the create path is exercised end-to-end
 * through the real {@code UserServiceMapper} + DAOs.
 *
 * <p><b>ABAC (R9.1, D-abac).</b> User_Work_Categories edits ride the <em>existing</em> {@code USERS}
 * resource on {@code UserController} ({@code @PermissionResource("USERS")}); this spec adds no new
 * resource and no new endpoint, so the create / edit stay guarded by the already-present {@code USERS}
 * grants — the Spring context boot itself asserts the controller's permission annotations are
 * consistent via {@code PermissionAnnotationValidator}. These service-level tests exercise the
 * work-category persistence / validation behind that guard.
 *
 * <p><b>Repeatability.</b> Every fixture row carries a unique per-run suffix ({@code System.nanoTime()}),
 * and {@link #tearDown()} removes every user, work category, and role this test created, so the suite
 * re-runs without manual DB cleanup.
 *
 * <p>Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.5, 9.1
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
@DisplayName("UserService — User_Work_Categories (FOR-05-10b R1)")
class UserWorkCategoriesIntegrationTest {

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
    private UserService userService;
    @Autowired
    private UserDao userDao;
    @Autowired
    private RoleDao roleDao;
    @Autowired
    private WorkCategoryDao workCategoryDao;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private InviteTokenDao inviteTokenDao;
    @MockitoBean
    private InvitationMailSender invitationMailSender;

    private final String runId = Long.toString(System.nanoTime());

    private Long workerRoleId;
    private Long managerRoleId;
    private WorkCategoryEntity catPaint;      // active
    private WorkCategoryEntity catTile;       // active
    private WorkCategoryEntity catInactive;   // inactive

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));
        // The create path's afterCreate hook (InviteService.issueInvite) mints a token via
        // inviteTokenDao.save(...); return a non-null stub so the invite link builds (no real email,
        // the mail sender is mocked). This test does not assert on invites.
        when(inviteTokenDao.save(any(InviteTokenEntity.class)))
                .thenAnswer(inv -> {
                    InviteTokenEntity t = inv.getArgument(0);
                    t.setToken(t.getToken() == null ? "stub-token" : t.getToken());
                    return t;
                });
        workerRoleId = seedRole("WORKER", "Работник", "Pracownik");
        managerRoleId = seedRole("MANAGER", "Менеджер", "Menedżer");
        catPaint = seedCategory("PAINT", 1, "Покраска", "Malowanie", true);
        catTile = seedCategory("TILE", 2, "Плитка", "Płytki", true);
        catInactive = seedCategory("OLD", 3, "Старое", "Stare", false);
    }

    @AfterEach
    void tearDown() {
        // Clean up inside a transaction so the LAZY work-category collections can be cleared before
        // the user rows (and their user_work_categories links) are deleted.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            java.util.stream.StreamSupport.stream(userDao.findAll().spliterator(), false)
                    .filter(u -> u.getEmail() != null && u.getEmail().contains(runId))
                    .forEach(u -> {
                        u.getWorkCategories().clear();
                        userDao.save(u);
                        userDao.delete(u);
                    });
            java.util.stream.StreamSupport.stream(workCategoryDao.findAll().spliterator(), false)
                    .filter(c -> c.getCode().endsWith(runId))
                    .forEach(workCategoryDao::delete);
        });
        // Roles (WORKER / MANAGER) are shared, harmless seed rows and are left in place so a sibling
        // test sharing this Spring context is not disturbed; only the per-run users and categories
        // this test created are removed.
        LocaleContextHolder.resetLocaleContext();
    }

    // ---------------------------------------------------------------------------------------------
    // R1.1 — accept an ordered, distinct, active id list for a WORKER user, persist preserving order
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("R1.1 — WORKER create persists the ordered categories; read returns them in order")
    void workerCreatePersistsOrderedCategories() {
        // Submit in TILE, PAINT order (not catalog order) to prove submission order is preserved.
        UserServiceExtendedModel created = userService.create(
                model(workerRoleId, List.of(catTile.getId(), catPaint.getId())));

        assertThat(persistedCategoryIds(created.id()))
                .containsExactly(catTile.getId(), catPaint.getId());

        // R1.5 — the read model carries the ordered refs with the ru localized name.
        UserServiceExtendedModel read = readModel(created.id());
        assertThat(read.workCategories())
                .extracting(RefDto::id)
                .containsExactly(catTile.getId(), catPaint.getId());
        assertThat(read.workCategories())
                .extracting(RefDto::name)
                .containsExactly("Плитка", "Покраска");
    }

    @Test
    @DisplayName("R1.1 — an empty list is accepted and persists no categories")
    void workerCreateEmptyList() {
        UserServiceExtendedModel created = userService.create(model(workerRoleId, List.of()));
        assertThat(persistedCategoryIds(created.id())).isEmpty();
        assertThat(readModel(created.id()).workCategories()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // R1.2 — duplicate id rejected, nothing persisted
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("R1.2 — duplicate id -> error.user.workCategories.duplicate, no user created")
    void duplicateIdRejected() {
        long before = userDao.count();
        ForemenApiException ex = catchThrowableOfType(
                () -> userService.create(model(workerRoleId, List.of(catPaint.getId(), catPaint.getId()))),
                ForemenApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getMessageCode()).isEqualTo("error.user.workCategories.duplicate");
        assertThat(userDao.count()).isEqualTo(before); // changed nothing
    }

    // ---------------------------------------------------------------------------------------------
    // R1.3 — unknown / inactive id rejected, nothing persisted
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("R1.3 — unknown id -> error.user.workCategories.invalid, no user created")
    void unknownIdRejected() {
        long before = userDao.count();
        ForemenApiException ex = catchThrowableOfType(
                () -> userService.create(model(workerRoleId, List.of(999_999_999L))),
                ForemenApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getMessageCode()).isEqualTo("error.user.workCategories.invalid");
        assertThat(userDao.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("R1.3 — inactive category id -> error.user.workCategories.invalid")
    void inactiveIdRejected() {
        ForemenApiException ex = catchThrowableOfType(
                () -> userService.create(model(workerRoleId, List.of(catInactive.getId()))),
                ForemenApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getMessageCode()).isEqualTo("error.user.workCategories.invalid");
    }

    // ---------------------------------------------------------------------------------------------
    // R1.4 — ignored / cleared for a non-WORKER user
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("R1.4 — non-WORKER create ignores the submitted list, keeps the user's list empty")
    void nonWorkerCreateIgnoresList() {
        UserServiceExtendedModel created = userService.create(
                model(managerRoleId, List.of(catPaint.getId(), catTile.getId())));

        assertThat(persistedCategoryIds(created.id())).isEmpty();
        // R1.5 — a non-WORKER user carries an empty workCategories read list.
        assertThat(readModel(created.id()).workCategories()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // R1.1 (edit) — update replaces the list, preserving the new order; non-WORKER clears it
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("R1.1 — WORKER edit replaces the list with the new ordered selection")
    void workerEditReplacesList() {
        UserServiceExtendedModel created = userService.create(
                model(workerRoleId, List.of(catPaint.getId())));

        UserServiceExtendedModel updated = userService.update(created.id(),
                updateModel(created.id(), workerRoleId, List.of(catTile.getId(), catPaint.getId())));

        assertThat(updated.workCategories())
                .extracting(RefDto::id)
                .containsExactly(catTile.getId(), catPaint.getId());
        assertThat(persistedCategoryIds(created.id()))
                .containsExactly(catTile.getId(), catPaint.getId());
    }

    @Test
    @DisplayName("R1.4 — editing a user to a non-WORKER role clears the categories")
    void editToNonWorkerClears() {
        UserServiceExtendedModel created = userService.create(
                model(workerRoleId, List.of(catPaint.getId(), catTile.getId())));
        assertThat(persistedCategoryIds(created.id())).hasSize(2);

        userService.update(created.id(),
                updateModel(created.id(), managerRoleId, List.of(catPaint.getId())));

        assertThat(persistedCategoryIds(created.id())).isEmpty();
    }

    @Test
    @DisplayName("R1.2 (edit) — a duplicate id on update is rejected and the stored list is unchanged")
    void editDuplicateRejectedUnchanged() {
        UserServiceExtendedModel created = userService.create(
                model(workerRoleId, List.of(catPaint.getId())));

        ForemenApiException ex = catchThrowableOfType(
                () -> userService.update(created.id(),
                        updateModel(created.id(), workerRoleId, List.of(catTile.getId(), catTile.getId()))),
                ForemenApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getMessageCode()).isEqualTo("error.user.workCategories.duplicate");
        // The original single-category list is unchanged.
        assertThat(persistedCategoryIds(created.id())).containsExactly(catPaint.getId());
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private UserServiceExtendedModel model(Long roleId, List<Long> workCategoryIds) {
        return new UserServiceExtendedModel(
                null, "WC User " + runId, "wc+" + System.nanoTime() + "+" + runId + "@example.com",
                null, roleId, null, true, "ru", null, workCategoryIds, null);
    }

    /**
     * Invokes {@code userService.findById} inside a transaction so the read-model mapper can
     * initialize the user's LAZY associations (role name, work categories) while a session is open
     * — the {@code findById} default service method is not itself transactional, so the test owns
     * the boundary (mirrors how the production HTTP request carries one).
     */
    private UserServiceExtendedModel readModel(Long userId) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> userService.findById(userId));
    }

    /**
     * Reads a persisted user's ordered Work_Category ids inside a transaction so the LAZY
     * {@code @ManyToMany} collection can be initialized (the test method itself runs outside any
     * session). Returns the ids in persisted order.
     */
    private List<Long> persistedCategoryIds(Long userId) {
        return new TransactionTemplate(transactionManager).execute(status ->
                userDao.findById(userId).orElseThrow().getWorkCategories().stream()
                        .map(WorkCategoryEntity::getId)
                        .toList());
    }

    private UserServiceExtendedModel updateModel(Long id, Long roleId, List<Long> workCategoryIds) {
        UserEntity existing = userDao.findById(id).orElseThrow();
        return new UserServiceExtendedModel(
                id, existing.getName(), existing.getEmail(), null, roleId, null, true, "ru",
                null, workCategoryIds, null);
    }

    private Long seedRole(String code, String nameRu, String namePl) {
        return roleDao.findByCode(code).map(RoleEntity::getId).orElseGet(() -> {
            RoleEntity role = new RoleEntity();
            role.setCode(code);
            role.setNameRU(nameRu);
            role.setNamePL(namePl);
            role.setSystem(false);
            return roleDao.save(role).getId();
        });
    }

    private WorkCategoryEntity seedCategory(String code, int orderNo, String nameRu, String namePl, boolean active) {
        WorkCategoryEntity c = new WorkCategoryEntity();
        c.setCode(code + "-" + runId);
        c.setOrderNo(orderNo);
        c.setNameRU(nameRu);
        c.setNamePL(namePl);
        c.setActive(active);
        return workCategoryDao.save(c);
    }
}
