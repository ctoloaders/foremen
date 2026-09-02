package com.foremen.scoping;

import com.foremen.dao.AdminDao;
import org.springframework.stereotype.Repository;

/**
 * Test-only repository for {@link ScopedFixtureProject}, used to persist the association target so
 * a {@link ScopedFixtureEntity} can reach its project id through the {@code project.id} dotted path.
 */
@Repository
public interface ScopedFixtureProjectDao extends AdminDao<ScopedFixtureProject, Long> {
}
