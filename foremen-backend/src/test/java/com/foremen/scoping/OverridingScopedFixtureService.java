package com.foremen.scoping;

import com.foremen.service.ProjectAccessCache;
import com.foremen.service.audit.AuditLogDao;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;

/**
 * Test-only variant of {@link ScopedFixtureService} that overrides {@link #getProjectId(Long)} with a
 * bespoke resolution, proving the FOR-03-04a contract honors a custom override instead of the default
 * {@code getProjectIdPath()}-derived resolver (Requirement 1.4).
 *
 * <p>Instead of the Criteria query the default runs, it looks the owning project id up directly
 * through the {@link ScopedFixtureDao} and reads it off the loaded {@link ScopedFixtureEntity}. A test
 * can pre-load a canned answer via {@link #setOverriddenProjectId(Long)} to assert the override path is
 * taken rather than the default resolver. It changes NOTHING else: it inherits all CRUD plumbing, the
 * by-id overrides, the list filter, and {@code assertProjectAccess} from {@link ScopedFixtureService} /
 * {@link com.foremen.service.ProjectScopedService}.</p>
 */
@Service
public class OverridingScopedFixtureService extends ScopedFixtureService {

    private final ScopedFixtureDao scopedFixtureDao;

    /** Canned answer a test can inject to prove the override (not the default resolver) was consulted. */
    private volatile Long overriddenProjectId;

    /** Records whether the bespoke {@link #getProjectId(Long)} was invoked, for assertions. */
    private volatile boolean getProjectIdCalled;

    public OverridingScopedFixtureService(ScopedFixtureDao scopedFixtureDao,
                                          ScopedFixtureMapper scopedFixtureMapper,
                                          ProjectAccessCache projectAccessCache,
                                          AuditLogDao auditLogDao,
                                          EntityManager entityManager) {
        super(scopedFixtureDao, scopedFixtureMapper, projectAccessCache, auditLogDao, entityManager);
        this.scopedFixtureDao = scopedFixtureDao;
    }

    /**
     * Bespoke owning-project resolution (Requirement 1.4): returns the canned override when set,
     * otherwise reads the owning project id directly off the entity via the DAO. Returns {@code null}
     * when the entity does not exist, matching the default's absence contract.
     */
    @Override
    public Long getProjectId(Long entityId) {
        this.getProjectIdCalled = true;
        if (overriddenProjectId != null) {
            return overriddenProjectId;
        }
        return scopedFixtureDao.findById(entityId)
                .map(ScopedFixtureEntity::getProjectId)
                .orElse(null);
    }

    /** Injects a canned answer so a test can prove the override path is honored. */
    public void setOverriddenProjectId(Long overriddenProjectId) {
        this.overriddenProjectId = overriddenProjectId;
    }

    /** True iff the bespoke {@link #getProjectId(Long)} was invoked since the last reset. */
    public boolean wasGetProjectIdCalled() {
        return getProjectIdCalled;
    }

    /** Resets the invocation flag between assertions. */
    public void resetGetProjectIdCalled() {
        this.getProjectIdCalled = false;
    }
}
