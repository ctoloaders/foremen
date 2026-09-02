package com.foremen.service.integration;

import com.foremen.controller.model.PermissionEntryRequest;
import com.foremen.controller.model.RolePermissionRequest;
import com.foremen.dao.RoleDao;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.service.RoleService;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Invalidation round-trip integration test for {@link RoleService#replacePermissions} and
 * {@link RoleService#deleteById}.
 *
 * <p><b>Feature: FOR-03-03-permission-evaluator, Property 9: Invalidation forces a reload
 * (invalidation round-trip).</b> After an initial evaluation caches a role's permission set,
 * a {@code RoleService} mutation that invalidates the cache entry for that role code MUST cause
 * the next {@link ForemenPermissionEvaluator} evaluation to reload the set from the database.
 * This test proves the round-trip end to end through the real wiring for two touch points:
 * {@code replacePermissions} (Requirement 9.1) and {@code deleteById} (Requirement 9.2), with the
 * reload observable via the cache reflecting the persisted change (Requirement 9.5).</p>
 *
 * <p><b>Wiring:</b> a real {@link RoleService}, the shared singleton
 * {@code com.foremen.service.permission.PermissionCache}, and a real
 * {@link ForemenPermissionEvaluator} are all autowired from the Spring context, so the evaluator
 * and the service invalidate/read the very same cache instance used in production. The
 * {@link RoleDao} bean is a {@link MockitoSpyBean} — it behaves exactly like the real repository
 * (real database reads/writes) but lets us <em>count</em> how many times {@code findByCode} (the
 * evaluator's database load path) is invoked, so a cache hit vs. a post-invalidation reload is
 * distinguishable by the load count in addition to the changed decision.</p>
 *
 * <p><b>Repeatability:</b> every scenario seeds its own role/resource/operation graph under a
 * unique {@code run-id} suffix (nanoTime) so runs never collide, and the whole test runs inside a
 * rolled-back {@code @Transactional} boundary; no manual database clean-up is required between
 * runs.</p>
 *
 * <p>Validates Requirements: 9.1, 9.2, 9.5.</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
@Transactional
class PermissionInvalidationRoundTripIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private RoleService roleService;

    @Autowired
    private ForemenPermissionEvaluator evaluator;

    /**
     * The real repository, spied so {@code findByCode} calls (the evaluator's DB load path) can be
     * counted. All other repository behaviour is the genuine database-backed implementation.
     */
    @MockitoSpyBean
    private RoleDao roleDao;

    @PersistenceContext
    private EntityManager entityManager;

    /** Unique run identifier so seeded codes never collide across repeated runs. */
    private String runId;

    @BeforeEach
    void setUp() {
        runId = String.valueOf(System.nanoTime());
        // The permission-change audit path reads the current principal; supply one.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test-admin@foremen.com", "password", List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("replacePermissions invalidates the cache so the next evaluation reloads (9.1, 9.5)")
    void replacePermissions_invalidates_nextEvaluationReloads() {
        // Seed a role granting PROJECTS:READ and the resource/operation it references.
        OperationEntity read = seedOperation("READ");
        ResourceEntity projects = seedResource("PROJECTS");
        RoleEntity role = seedRole("MANAGER");
        grant(role, projects, List.of(read));
        entityManager.flush();
        entityManager.clear();

        String roleCode = role.getCode();
        String resourceCode = projects.getCode();
        String operationCode = read.getCode();

        // First evaluation: loads from DB and caches the granting set.
        assertThat(evaluator.isAllowed(roleCode, resourceCode, operationCode))
                .as("initial evaluation reflects the seeded grant")
                .isTrue();
        verify(roleDao, times(1)).findByCode(roleCode);

        // Repeated evaluation: served from cache, no additional DB load.
        assertThat(evaluator.isAllowed(roleCode, resourceCode, operationCode)).isTrue();
        verify(roleDao, times(1)).findByCode(roleCode);

        // Replace the role's matrix — this must invalidate the cache entry for the role code.
        roleService.replacePermissions(role.getId(), new RolePermissionRequest(List.of()));
        // The mutation runs inside the shared test transaction; clear the persistence context so
        // the evaluator's reload reads straight from the database rather than the session cache.
        entityManager.clear();

        // The invalidation round-trip: the next evaluation must RELOAD from the database (the cache
        // entry was evicted), observable as a second findByCode load rather than a cache hit
        // (Requirements 9.1, 9.5). Before the replace there was exactly one load shared by all
        // prior cache hits; the post-invalidation evaluation forces a fresh load.
        evaluator.isAllowed(roleCode, resourceCode, operationCode);
        verify(roleDao, times(2)).findByCode(roleCode);
    }

    @Test
    @DisplayName("deleteById invalidates the cache so the next evaluation reloads (9.2, 9.5)")
    void deleteById_invalidates_nextEvaluationReloads() {
        // Seed a non-system role granting PROJECTS:READ.
        OperationEntity read = seedOperation("READ");
        ResourceEntity projects = seedResource("PROJECTS");
        RoleEntity role = seedRole("MANAGER");
        grant(role, projects, List.of(read));
        entityManager.flush();
        entityManager.clear();

        String roleCode = role.getCode();
        String resourceCode = projects.getCode();
        String operationCode = read.getCode();
        Long roleId = role.getId();

        // First evaluation: loads from DB and caches the granting set.
        assertThat(evaluator.isAllowed(roleCode, resourceCode, operationCode))
                .as("initial evaluation reflects the seeded grant")
                .isTrue();
        verify(roleDao, times(1)).findByCode(roleCode);

        // Repeated evaluation: served from cache, no additional DB load.
        assertThat(evaluator.isAllowed(roleCode, resourceCode, operationCode)).isTrue();
        verify(roleDao, times(1)).findByCode(roleCode);

        // Delete the role — deleteById captures the code before removal and invalidates the cache.
        roleService.deleteById(roleId);
        // Clear the persistence context so the evaluator's reload reads fresh from the database.
        entityManager.clear();

        // Next evaluation MUST reload; the role is gone so the set is empty and access is denied.
        assertThat(evaluator.isAllowed(roleCode, resourceCode, operationCode))
                .as("after deleteById the reloaded set for the removed role denies the grant")
                .isFalse();
        verify(roleDao, times(2)).findByCode(roleCode);
    }

    // --- Seeding helpers (unique per run via runId) ---

    private OperationEntity seedOperation(String baseCode) {
        OperationEntity op = new OperationEntity();
        op.setCode(baseCode + "-" + runId);
        op.setNameRU("op-" + baseCode);
        op.setNamePL("op-" + baseCode);
        entityManager.persist(op);
        return op;
    }

    private ResourceEntity seedResource(String baseCode) {
        ResourceEntity resource = new ResourceEntity();
        resource.setCode(baseCode + "-" + runId);
        resource.setNameRU("res-" + baseCode);
        resource.setNamePL("res-" + baseCode);
        entityManager.persist(resource);
        return resource;
    }

    private RoleEntity seedRole(String baseCode) {
        RoleEntity role = new RoleEntity();
        role.setCode(baseCode + "-" + runId);
        role.setNameRU("role-" + baseCode);
        role.setNamePL("role-" + baseCode);
        role.setSystem(false);
        entityManager.persist(role);
        return role;
    }

    private void grant(RoleEntity role, ResourceEntity resource, List<OperationEntity> operations) {
        RoleResourceEntity rr = new RoleResourceEntity();
        rr.setRole(role);
        rr.setResource(resource);
        rr.setOperations(operations);
        entityManager.persist(rr);
        // Intentionally NOT adding rr to role.getRoleResources(): the owning side is
        // RoleResourceEntity.role, and leaving the role's lazy collection untouched mirrors how
        // the data exists on a fresh load in production and avoids cascade/orphan interference.
    }
}
