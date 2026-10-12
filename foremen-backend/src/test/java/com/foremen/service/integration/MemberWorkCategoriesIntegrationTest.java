package com.foremen.service.integration;

import com.foremen.controller.model.RefDto;
import com.foremen.controller.model.TeamMemberView;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectMemberService;
import com.foremen.service.offer.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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

/**
 * Spring integration test for FOR-05-10b task 3.2 — Member_Work_Categories on the project-membership
 * flow ({@code ProjectMemberService.assign} copy-on-create, {@code updateAttributes} PATCH replace,
 * independence from the user default, cascade-on-remove, and the Team_Member_View read; Requirements
 * 2.1–2.5, 9.1).
 *
 * <p>Boot harness mirrors {@link UserWorkCategoriesIntegrationTest}: {@code @SpringBootTest} +
 * {@code @Testcontainers} + {@code @ActiveProfiles("integration-test")}, where Liquibase is disabled
 * and Hibernate {@code create-drop} builds the schema from the mapped entities (so the
 * {@code project_member_work_categories} and {@code user_work_categories} join tables are built from
 * the {@link ProjectMemberEntity} / {@link UserEntity} {@code @ManyToMany} mappings). The
 * {@link NotificationService} is mocked so the best-effort assign/update notifications send nothing.
 *
 * <p><b>ABAC (R9.1, D-abac).</b> Member_Work_Categories edits ride the <em>existing</em>
 * {@code PROJECT_MEMBERS} resource on {@code ProjectMemberController} ({@code @PermissionResource}
 * + method {@code @PermissionOperation(UPDATE)} on the PATCH handler); this spec adds no new resource
 * and no new endpoint — the context boot asserts the controller's permission annotations are
 * consistent via {@code PermissionAnnotationValidator}. These service-level tests exercise the
 * work-category copy / replace / cascade behind that guard; the caller is authenticated ADMIN so the
 * project-scope gate is bypassed and no projects row needs seeding.
 *
 * <p><b>Repeatability.</b> Every fixture row carries a unique per-run suffix ({@code System.nanoTime()}),
 * each test uses a fresh {@code projectId} derived from the run id, and {@link #tearDown()} removes
 * every membership, user, and work category this test created, so the suite re-runs without manual
 * DB cleanup.
 *
 * <p>Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 9.1
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
@DisplayName("ProjectMemberService — Member_Work_Categories (FOR-05-10b R2)")
class MemberWorkCategoriesIntegrationTest {

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
    private ProjectMemberService projectMemberService;
    @Autowired
    private ProjectMemberDao projectMemberDao;
    @Autowired
    private UserDao userDao;
    @Autowired
    private RoleDao roleDao;
    @Autowired
    private WorkCategoryDao workCategoryDao;
    @Autowired
    private ProjectDao projectDao;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private NotificationService notificationService;

    private final long runId = System.nanoTime();
    // A real projects row is seeded per run (setUp) so the project_members.project_id -> projects FK
    // holds; using a synthetic id would surface as a misleading error.project.member.duplicate
    // because persistUnique maps every DataIntegrityViolationException to the duplicate code.
    private long projectId;

    private Long workerRoleId;
    private Long managerRoleId;
    private WorkCategoryEntity catPaint;      // active
    private WorkCategoryEntity catTile;       // active
    private WorkCategoryEntity catWiring;     // active
    private WorkCategoryEntity catInactive;   // inactive

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));
        // Authenticate as ADMIN (numeric principal) so the project-scope gate is bypassed and the
        // audit performedBy resolves to the numeric id.
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        workerRoleId = seedRole("WORKER", "Работник", "Pracownik");
        managerRoleId = seedRole("MANAGER", "Менеджер", "Menedżer");
        catPaint = seedCategory("PAINT", 1, "Покраска", "Malowanie", true);
        catTile = seedCategory("TILE", 2, "Плитка", "Płytki", true);
        catWiring = seedCategory("WIRE", 3, "Электрика", "Instalacja", true);
        catInactive = seedCategory("OLD", 4, "Старое", "Stare", false);

        // Seed a real projects row so project_members.project_id FK holds (create-drop schema).
        ProjectEntity project = new ProjectEntity();
        project.setName("WC Member Project " + runId);
        project.setStatus(ProjectStatus.DRAFT);
        projectId = projectDao.save(project).getId();
    }

    @AfterEach
    void tearDown() {
        // Clean up inside a transaction so the LAZY work-category collections can be cleared before
        // the owning member / user rows (and their join links) are deleted.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            projectMemberDao.findByProjectId(projectId).forEach(m -> {
                m.getWorkCategories().clear();
                projectMemberDao.save(m);
                projectMemberDao.delete(m);
            });
            java.util.stream.StreamSupport.stream(userDao.findAll().spliterator(), false)
                    .filter(u -> u.getEmail() != null && u.getEmail().contains(Long.toString(runId)))
                    .forEach(u -> {
                        u.getWorkCategories().clear();
                        userDao.save(u);
                        userDao.delete(u);
                    });
            java.util.stream.StreamSupport.stream(workCategoryDao.findAll().spliterator(), false)
                    .filter(c -> c.getCode().endsWith(Long.toString(runId)))
                    .forEach(workCategoryDao::delete);
            projectDao.findById(projectId).ifPresent(projectDao::delete);
        });
        SecurityContextHolder.clearContext();
        LocaleContextHolder.resetLocaleContext();
    }

    // ---------------------------------------------------------------------------------------------
    // R2.1 — on WORKER membership create, Member_Work_Categories is an ordered COPY of the user default
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("R2.1 — WORKER assign copies the user's ordered default categories into the member")
    void assignCopiesUserDefaultCategories() {
        // User default = [TILE, PAINT] (deliberately not catalog order).
        UserEntity worker = seedWorker(List.of(catTile, catPaint));

        ProjectMemberEntity member = projectMemberService.assign(worker.getId(), projectId, workerRoleId);

        assertThat(memberCategoryIds(member.getId()))
                .containsExactly(catTile.getId(), catPaint.getId());
    }

    @Test
    @DisplayName("R2.1 — a WORKER with an empty default yields an empty member list")
    void assignEmptyDefaultYieldsEmptyMember() {
        UserEntity worker = seedWorker(List.of());
        ProjectMemberEntity member = projectMemberService.assign(worker.getId(), projectId, workerRoleId);
        assertThat(memberCategoryIds(member.getId())).isEmpty();
    }

    @Test
    @DisplayName("R2.1 — a non-WORKER member never carries work categories")
    void assignNonWorkerHasNoCategories() {
        UserEntity manager = seedUser(managerRoleId, List.of());
        ProjectMemberEntity member = projectMemberService.assign(manager.getId(), projectId, managerRoleId);
        assertThat(memberCategoryIds(member.getId())).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // R2.2 — PATCH replaces the member list; R2.4 Team_Member_View returns the ordered refs
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("R2.2 — PATCH replaces the member list with the submitted ordered selection; view returns refs")
    void patchReplacesMemberList() {
        UserEntity worker = seedWorker(List.of(catPaint));
        ProjectMemberEntity member = projectMemberService.assign(worker.getId(), projectId, workerRoleId);

        TeamMemberView view = projectMemberService.updateAttributes(
                worker.getId(), projectId, null, null, null, List.of(catWiring.getId(), catTile.getId()));

        // R2.4 — the view carries the ordered refs with the ru localized name.
        assertThat(view.workCategories()).extracting(RefDto::id)
                .containsExactly(catWiring.getId(), catTile.getId());
        assertThat(view.workCategories()).extracting(RefDto::name)
                .containsExactly("Электрика", "Плитка");

        // The persisted member list is replaced (not merged).
        assertThat(memberCategoryIds(member.getId()))
                .containsExactly(catWiring.getId(), catTile.getId());
    }

    @Test
    @DisplayName("R2.2 — PATCH with an explicit empty list clears the member's categories")
    void patchEmptyListClears() {
        UserEntity worker = seedWorker(List.of(catPaint, catTile));
        ProjectMemberEntity member = projectMemberService.assign(worker.getId(), projectId, workerRoleId);

        projectMemberService.updateAttributes(worker.getId(), projectId, null, null, null, List.of());

        assertThat(memberCategoryIds(member.getId())).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // R2.2 — duplicate / invalid id rejected with the member-scoped codes, nothing changed
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("R2.2 — duplicate id -> error.member.workCategories.duplicate, list unchanged")
    void patchDuplicateRejected() {
        UserEntity worker = seedWorker(List.of(catPaint));
        projectMemberService.assign(worker.getId(), projectId, workerRoleId);

        ForemenApiException ex = catchThrowableOfType(
                () -> projectMemberService.updateAttributes(worker.getId(), projectId, null, null, null,
                        List.of(catTile.getId(), catTile.getId())),
                ForemenApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getMessageCode()).isEqualTo("error.member.workCategories.duplicate");
        // Unchanged: still the single copied-from-default category.
        assertThat(reloadCategories(worker.getId())).containsExactly(catPaint.getId());
    }

    @Test
    @DisplayName("R2.2 — unknown id -> error.member.workCategories.invalid, list unchanged")
    void patchUnknownRejected() {
        UserEntity worker = seedWorker(List.of(catPaint));
        projectMemberService.assign(worker.getId(), projectId, workerRoleId);

        ForemenApiException ex = catchThrowableOfType(
                () -> projectMemberService.updateAttributes(worker.getId(), projectId, null, null, null,
                        List.of(999_999_999L)),
                ForemenApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getMessageCode()).isEqualTo("error.member.workCategories.invalid");
        assertThat(reloadCategories(worker.getId())).containsExactly(catPaint.getId());
    }

    @Test
    @DisplayName("R2.2 — inactive category id -> error.member.workCategories.invalid")
    void patchInactiveRejected() {
        UserEntity worker = seedWorker(List.of(catPaint));
        projectMemberService.assign(worker.getId(), projectId, workerRoleId);

        ForemenApiException ex = catchThrowableOfType(
                () -> projectMemberService.updateAttributes(worker.getId(), projectId, null, null, null,
                        List.of(catInactive.getId())),
                ForemenApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getMessageCode()).isEqualTo("error.member.workCategories.invalid");
    }

    // ---------------------------------------------------------------------------------------------
    // R2.3 — editing the member list never touches the user default, and vice versa (independence)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("R2.3 — editing the member list leaves the user default unchanged")
    void memberEditDoesNotTouchUserDefault() {
        UserEntity worker = seedWorker(List.of(catPaint, catTile));
        projectMemberService.assign(worker.getId(), projectId, workerRoleId);

        projectMemberService.updateAttributes(worker.getId(), projectId, null, null, null,
                List.of(catWiring.getId()));

        // The user's default list is UNCHANGED by the member edit (R2.3).
        assertThat(userCategoryIds(worker.getId()))
                .containsExactly(catPaint.getId(), catTile.getId());
        // The member list reflects the edit.
        assertThat(reloadCategories(worker.getId())).containsExactly(catWiring.getId());
    }

    @Test
    @DisplayName("R2.3 — editing the user default leaves an already-copied member list unchanged")
    void userEditDoesNotTouchMemberList() {
        UserEntity worker = seedWorker(List.of(catPaint));
        projectMemberService.assign(worker.getId(), projectId, workerRoleId);

        // Change the user default directly after the copy (inside a transaction so the LAZY
        // collection initializes before it is mutated).
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            UserEntity reloaded = userDao.findById(worker.getId()).orElseThrow();
            reloaded.getWorkCategories().clear();
            reloaded.getWorkCategories().add(catWiring);
            userDao.save(reloaded);
        });

        // The member list keeps its copied value, independent of the user default (R2.4 / R2.3).
        assertThat(reloadCategories(worker.getId())).containsExactly(catPaint.getId());
    }

    // ---------------------------------------------------------------------------------------------
    // R2.5 — removing a member cascades away its project_member_work_categories rows
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("R2.5 — removing the member discards its work-category links (no orphan)")
    void removeCascadesCategories() {
        UserEntity worker = seedWorker(List.of(catPaint, catTile));
        ProjectMemberEntity member = projectMemberService.assign(worker.getId(), projectId, workerRoleId);
        Long memberId = member.getId();
        assertThat(memberCategoryIds(memberId)).hasSize(2);

        projectMemberService.remove(worker.getId(), projectId);

        // The membership and its category links are gone; the referenced categories survive (RESTRICT).
        assertThat(projectMemberDao.findById(memberId)).isEmpty();
        assertThat(workCategoryDao.findById(catPaint.getId())).isPresent();
        assertThat(workCategoryDao.findById(catTile.getId())).isPresent();
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** The member's ordered Work_Category ids, read inside a transaction (LAZY collection). */
    private List<Long> reloadCategories(Long userId) {
        return new TransactionTemplate(transactionManager).execute(status ->
                projectMemberDao.findByUserIdAndProjectId(userId, projectId).orElseThrow()
                        .getWorkCategories().stream().map(WorkCategoryEntity::getId).toList());
    }

    /** A member's ordered Work_Category ids by member id, read inside a transaction. */
    private List<Long> memberCategoryIds(Long memberId) {
        return new TransactionTemplate(transactionManager).execute(status ->
                projectMemberDao.findById(memberId).orElseThrow()
                        .getWorkCategories().stream().map(WorkCategoryEntity::getId).toList());
    }

    /** A user's ordered default Work_Category ids, read inside a transaction. */
    private List<Long> userCategoryIds(Long userId) {
        return new TransactionTemplate(transactionManager).execute(status ->
                userDao.findById(userId).orElseThrow()
                        .getWorkCategories().stream().map(WorkCategoryEntity::getId).toList());
    }

    private UserEntity seedWorker(List<WorkCategoryEntity> defaults) {
        return seedUser(workerRoleId, defaults);
    }

    private UserEntity seedUser(Long roleId, List<WorkCategoryEntity> defaults) {
        UserEntity user = new UserEntity();
        user.setName("WC Member " + runId);
        user.setEmail("wcm+" + System.nanoTime() + "+" + runId + "@example.com");
        user.setRole(roleDao.findById(roleId).orElseThrow());
        user.setActive(true);
        user.setLocale("ru");
        user.getWorkCategories().addAll(defaults);
        return userDao.save(user);
    }

    private Long seedRole(String code, String nameRu, String namePl) {
        return roleDao.findByCode(code).map(RoleEntity::getId).orElseGet(() -> {
            RoleEntity role = new RoleEntity();
            role.setCode(code);
            role.setNameRU(nameRu);
            role.setNamePL(namePl);
            role.setSystem(true);
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
