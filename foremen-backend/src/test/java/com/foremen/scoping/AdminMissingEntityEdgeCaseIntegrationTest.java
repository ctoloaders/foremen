package com.foremen.scoping;

import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Edge-case integration test for FOR-03-04a project action validation (task 5.5).
 *
 * <p>Proves that when an ADMIN caller acts on a genuinely non-existent entity id, the request still
 * yields the framework's 404 {@code error.entity.not.found}, and that this 404 comes from the
 * inherited CRUD {@code findById} — NOT from the project-ownership decision. On the ADMIN path
 * {@link com.foremen.service.ProjectScopedService#assertProjectAccess(Object)} short-circuits to
 * allow (ADMIN_Bypass, Requirement 2.2) WITHOUT consulting {@link
 * com.foremen.service.ProjectScopedService#getProjectId(Object)}, so no owning-project query is
 * issued; control falls straight through to {@code AdminService.super.findById(id)}, which raises
 * {@code error.entity.not.found} for the missing row. The framework's own missing-id behavior is
 * therefore unchanged for ADMIN.
 *
 * <p>To confirm {@code getProjectId} is never consulted on the ADMIN path, this test uses a
 * throwaway subclass of {@link ScopedFixtureService} whose {@code getProjectId} override records
 * whether it was called and fails loudly if it ever is. Because the ADMIN bypass precedes any
 * resolution, the recorded flag must stay {@code false} even though the id resolves to no row.
 *
 * <p>Container/profile setup mirrors {@link ScopedFixtureFilteringIntegrationTest}: Testcontainers
 * postgres under {@code @ActiveProfiles("integration-test")} with Hibernate {@code create-drop}
 * building the fixture schema. All state is seeded under a unique {@code run-id} and the
 * {@link SecurityContextHolder} is cleared per test, so the suite re-runs without manual clean-up.
 *
 * <p>This class creates only its own test file and inlines its own seeding helpers; it does not
 * modify any shared fixture, DAO, mapper, profile, or existing test class.
 *
 * <p>Validates: Requirement 2.2
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
@Transactional
class AdminMissingEntityEdgeCaseIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            // stringtype=unspecified lets PostgreSQL implicitly cast the UserEntity JSON converter's
            // text value into the jsonb display_preferences column, matching the deployed app.
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
    private ScopedFixtureService scopedFixtureService;

    @Autowired
    private UserDao userDao;

    @Autowired
    private RoleDao roleDao;

    /** Unique run identifier so any seeded rows never collide across repeated runs. */
    private String runId;
    private RoleEntity role;

    @BeforeEach
    void setUp() {
        runId = String.valueOf(System.nanoTime());
        role = seedRole();
        scopedFixtureService.setProjectIdPath("projectId");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("ADMIN findById on a non-existent id yields framework 404 error.entity.not.found (2.2)")
    void adminFindByIdOnMissingEntityYieldsFrameworkNotFound() {
        UserEntity admin = seedUser();
        authenticate(admin.getId(), "ROLE_" + ForemenPermissionEvaluator.ADMIN_ROLE_CODE);

        // No row was ever seeded for this id, so no fixture entity exists.
        Long missingId = Long.MAX_VALUE;

        ForemenApiException ex = catchThrowableOfType(
                () -> scopedFixtureService.findById(missingId),
                ForemenApiException.class);

        assertThat(ex).isNotNull();
        // 404 comes from the framework findById (missing row), not from a project-ownership deny.
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getMessageCode()).isEqualTo("error.entity.not.found");
    }

    @Test
    @DisplayName("ADMIN bypass does NOT consult getProjectId even for a missing id (2.2)")
    void adminBypassDoesNotConsultGetProjectId() {
        UserEntity admin = seedUser();
        authenticate(admin.getId(), "ROLE_" + ForemenPermissionEvaluator.ADMIN_ROLE_CODE);

        // A service variant whose getProjectId records invocation and fails if ever called.
        GetProjectIdSpyService spy = new GetProjectIdSpyService(scopedFixtureService);

        Long missingId = Long.MAX_VALUE;

        // Still a framework 404 for the missing row...
        assertThatThrownBy(() -> spy.findById(missingId))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(t -> {
                    ForemenApiException ex = (ForemenApiException) t;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getMessageCode()).isEqualTo("error.entity.not.found");
                });

        // ...and getProjectId must NOT have been consulted on the ADMIN path (no query issued).
        assertThat(spy.getProjectIdCalled)
                .as("ADMIN bypass must short-circuit before resolving the owning project id")
                .isFalse();
    }

    // --- Seeding / auth helpers (unique per run via runId) ---

    private void authenticate(Long userId, String authority) {
        SecurityContextHolder.clearContext();
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), "n/a", List.of(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private RoleEntity seedRole() {
        RoleEntity r = new RoleEntity();
        r.setCode("ADMIN-EDGE-ROLE-" + runId);
        r.setNameRU("Роль");
        r.setNamePL("Rola");
        r.setSystem(false);
        return roleDao.save(r);
    }

    private UserEntity seedUser() {
        UserEntity u = new UserEntity();
        u.setName("Admin Edge Case Test User");
        u.setEmail("admin-edge+" + System.nanoTime() + "@example.com");
        u.setRole(role);
        return userDao.save(u);
    }

    /**
     * Wrapper over the wired {@link ScopedFixtureService} whose {@code getProjectId} override flips a
     * flag (and fails) so the test can prove the ADMIN bypass never reaches project-id resolution.
     * It reuses the real service's CRUD plumbing so {@code AdminService.super.findById} runs exactly
     * as the framework would.
     */
    private static final class GetProjectIdSpyService implements
            com.foremen.service.ProjectScopedService<ScopedFixtureServiceModel, ScopedFixtureServiceExtendedModel, ScopedFixtureEntity, Long> {

        private final ScopedFixtureService delegate;
        private boolean getProjectIdCalled;

        private GetProjectIdSpyService(ScopedFixtureService delegate) {
            this.delegate = delegate;
        }

        @Override
        public com.foremen.dao.AdminDao<ScopedFixtureEntity, Long> getDao() {
            return delegate.getDao();
        }

        @Override
        public com.foremen.service.audit.AuditLogDao getAuditLogDao() {
            return delegate.getAuditLogDao();
        }

        @Override
        public com.foremen.mapper.ServiceToDaoMapper<ScopedFixtureEntity, ScopedFixtureServiceModel, ScopedFixtureServiceExtendedModel> getMapper() {
            return delegate.getMapper();
        }

        @Override
        public jakarta.persistence.EntityManager getEntityManager() {
            return delegate.getEntityManager();
        }

        @Override
        public Class<ScopedFixtureEntity> getDaoModelClass() {
            return ScopedFixtureEntity.class;
        }

        @Override
        public String getProjectIdPath() {
            return delegate.getProjectIdPath();
        }

        @Override
        public java.util.Set<Long> allowedProjectIds(Long userId) {
            return delegate.allowedProjectIds(userId);
        }

        @Override
        public Long getProjectId(Long entityId) {
            getProjectIdCalled = true;
            throw new AssertionError("getProjectId must not be consulted on the ADMIN path");
        }
    }
}
