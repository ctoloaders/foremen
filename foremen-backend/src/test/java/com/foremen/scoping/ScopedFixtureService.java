package com.foremen.scoping;

import com.foremen.dao.AdminDao;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.ProjectScopedService;
import com.foremen.service.audit.AuditLogDao;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Test-only service proving the project-scoping mechanism end to end against a throwaway
 * {@link ScopedFixtureEntity}, since no real project-scoped entity exists yet (they arrive in
 * FOR-06).
 *
 * <p>Following the FOR-03-04a single-contract shape, it implements exactly one CRUD contract —
 * {@link ProjectScopedService} — and never a plain {@code AdminService} and never both
 * (Project_Scoped_Exclusivity). Because {@link ProjectScopedService} now
 * {@code extends AdminService}, the fixture supplies the standard CRUD plumbing
 * ({@link #getDao()}, {@link #getMapper()}, {@link #getEntityManager()},
 * {@link #getDaoModelClass()}, {@link #getAuditLogDao()}) plus the single per-entity override
 * {@link #getProjectIdPath()} and wires {@link #allowedProjectIds(Long)} to
 * {@link ProjectAccessCache#get(Long)}.</p>
 *
 * <p>It overrides NO CRUD method and does NOT override {@code addRequiredQuery()} or
 * {@code getProjectId()} — the list filter and the default project-id resolver are inherited from
 * {@link ProjectScopedService} (Requirements 1.1-1.4, 3.1, 4.1, 5.1, 7.3, 7.4).</p>
 *
 * <p>{@link #getProjectIdPath()} is mutable so a test can point the same fixture at either the
 * single-segment path ({@code "projectId"}, default) or the dotted association path
 * ({@code "project.id"}) to exercise both branches of the path resolver used by the list filter and
 * the default {@code getProjectId} resolver.</p>
 */
@Service
public class ScopedFixtureService
        implements ProjectScopedService<ScopedFixtureServiceModel, ScopedFixtureServiceExtendedModel, ScopedFixtureEntity, Long> {

    private final ScopedFixtureDao scopedFixtureDao;
    private final ScopedFixtureMapper scopedFixtureMapper;
    private final ProjectAccessCache projectAccessCache;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;

    /** The project-id path this service filters on; defaults to the single-segment {@code projectId}. */
    private volatile String projectIdPath = "projectId";

    public ScopedFixtureService(ScopedFixtureDao scopedFixtureDao,
                                ScopedFixtureMapper scopedFixtureMapper,
                                ProjectAccessCache projectAccessCache,
                                AuditLogDao auditLogDao,
                                EntityManager entityManager) {
        this.scopedFixtureDao = scopedFixtureDao;
        this.scopedFixtureMapper = scopedFixtureMapper;
        this.projectAccessCache = projectAccessCache;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
    }

    // --- CRUD plumbing (inherited from AdminService via ProjectScopedService) ---

    @Override
    public AdminDao<ScopedFixtureEntity, Long> getDao() {
        return scopedFixtureDao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<ScopedFixtureEntity, ScopedFixtureServiceModel, ScopedFixtureServiceExtendedModel> getMapper() {
        return scopedFixtureMapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<ScopedFixtureEntity> getDaoModelClass() {
        return ScopedFixtureEntity.class;
    }

    // --- ProjectScopedService overrides ---

    /** The single mandatory per-entity override point (Requirement 1.1). */
    @Override
    public String getProjectIdPath() {
        return projectIdPath;
    }

    /** Wires the allowed-project-ids lookup to the production cache. */
    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccessCache.get(userId);
    }

    // --- Test control ---

    /** Lets a test switch the filtered path between {@code "projectId"} and {@code "project.id"}. */
    public void setProjectIdPath(String projectIdPath) {
        this.projectIdPath = projectIdPath;
    }
}
