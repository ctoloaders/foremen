package com.foremen.scoping;

import com.foremen.dao.AdminDao;
import org.springframework.stereotype.Repository;

/**
 * Test-only repository for {@link ScopedFixtureEntity}. Extends {@link AdminDao} so
 * {@link ScopedFixtureService} can persist fixture rows and drive the specification-based reads
 * (find / findExtended / getCount) that {@link com.foremen.service.ProjectScopedService} filters.
 */
@Repository
public interface ScopedFixtureDao extends AdminDao<ScopedFixtureEntity, Long> {
}
