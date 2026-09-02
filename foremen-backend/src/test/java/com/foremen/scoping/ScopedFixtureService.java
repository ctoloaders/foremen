package com.foremen.scoping;

import com.foremen.dao.ReadOnlyAdminDao;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.ProjectScopedService;
import com.foremen.service.ReadOnlyAdminService;
import jakarta.persistence.EntityManager;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Test-only service proving the FOR-03-04 project-scoping mechanism end to end against a throwaway
 * {@link ScopedFixtureEntity}, since no real project-scoped entity exists yet (they arrive in
 * FOR-06).
 *
 * <p>It implements BOTH:
 * <ul>
 *     <li>{@link ReadOnlyAdminService} — supplying the CRUD read plumbing ({@code getMapper()},
 *         {@code getReadDao()}, {@code getEntityManager()}, {@code getDaoModelClass()}) so
 *         {@code find} / {@code findExtended} / {@code getCount} run through
 *         {@code buildFinalSpecification}; and</li>
 *     <li>{@link ProjectScopedService} — supplying the single per-entity override point
 *         {@link #getProjectIdPath()} and wiring {@link #allowedProjectIds(Long)} to
 *         {@link ProjectAccessCache#get(Long)}.</li>
 * </ul>
 *
 * <p>Because {@link ProjectScopedService#addRequiredQuery()} overrides the {@link ReadOnlyAdminService}
 * default of the same signature, {@code buildFinalSpecification} picks up the project filter
 * automatically — the concrete service reimplements nothing (Requirements 6.4, 6.10).
 *
 * <p>{@link #getProjectIdPath()} is mutable so a test can point the same fixture at either the
 * single-segment path ({@code "projectId"}, default) or the dotted association path
 * ({@code "project.id"}) to exercise both branches of the path resolver (Requirement 7.1, 7.2).
 */
@Service
public class ScopedFixtureService
        implements ReadOnlyAdminService<ScopedFixtureServiceModel, ScopedFixtureServiceExtendedModel, ScopedFixtureEntity, Long>,
        ProjectScopedService<ScopedFixtureEntity> {

    private final ScopedFixtureDao scopedFixtureDao;
    private final ScopedFixtureMapper scopedFixtureMapper;
    private final ProjectAccessCache projectAccessCache;
    private final EntityManager entityManager;

    /** The project-id path this service filters on; defaults to the single-segment {@code projectId}. */
    private volatile String projectIdPath = "projectId";

    public ScopedFixtureService(ScopedFixtureDao scopedFixtureDao,
                                ScopedFixtureMapper scopedFixtureMapper,
                                ProjectAccessCache projectAccessCache,
                                EntityManager entityManager) {
        this.scopedFixtureDao = scopedFixtureDao;
        this.scopedFixtureMapper = scopedFixtureMapper;
        this.projectAccessCache = projectAccessCache;
        this.entityManager = entityManager;
    }

    // --- ReadOnlyAdminService plumbing ---

    @Override
    public ServiceToDaoMapper<ScopedFixtureEntity, ScopedFixtureServiceModel, ScopedFixtureServiceExtendedModel> getMapper() {
        return scopedFixtureMapper;
    }

    @Override
    public ReadOnlyAdminDao<ScopedFixtureEntity, Long> getReadDao() {
        return scopedFixtureDao;
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

    /**
     * Both {@link ReadOnlyAdminService} and {@link ProjectScopedService} declare a default
     * {@code addRequiredQuery()} with the same signature, so the implementing class MUST resolve the
     * conflict explicitly. It picks the {@link ProjectScopedService} implementation (the one holding
     * the project-filter decision logic), which is exactly the behavior the design mandates
     * (Requirement 6.8, 6.10).
     */
    @Override
    public Specification<ScopedFixtureEntity> addRequiredQuery() {
        return ProjectScopedService.super.addRequiredQuery();
    }

    /** The single per-entity override point (Requirement 6.9, 6.10). */
    @Override
    public String getProjectIdPath() {
        return projectIdPath;
    }

    /** Wires the allowed-project-ids lookup to the production cache (Requirement 6.10). */
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
