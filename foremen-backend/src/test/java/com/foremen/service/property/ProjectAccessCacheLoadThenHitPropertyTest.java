package com.foremen.service.property;

import com.foremen.config.security.ProjectAccessProperties;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.service.ProjectAccessCache;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property test for the {@link ProjectAccessCache} load-then-hit behaviour.
 *
 * <p>Covers the design property assigned to task 3.2:</p>
 * <ul>
 *   <li><b>Property 3: Cache load-then-hit serves without reloading</b>
 *       &mdash; Validates Requirements 4.1, 4.2, 4.3</li>
 * </ul>
 *
 * <p>The cache under test is a REAL {@link ProjectAccessCache} constructed from
 * {@link ProjectAccessProperties} exactly as in production (default 1440-minute idle TTL), so the
 * caching semantics are the production ones. The database load is counted through a spy/counting
 * {@link ProjectMemberDao} (a Mockito mock whose {@code findDistinctProjectIdsByUserId} increments
 * a load counter), which lets us assert exactly how many times the DB is consulted across repeated
 * {@code get(userId)} calls with no intervening invalidation. The empty-membership case
 * (a user with no rows resolving to an empty set) is exercised by the generated set optionally
 * being empty.</p>
 */
class ProjectAccessCacheLoadThenHitPropertyTest {

    // Feature: FOR-03-04-project-ownership, Property 3: Cache load-then-hit serves without reloading.
    // For any user id and any N >= 1 repeated get(userId) calls with no intervening invalidation,
    // the allowed-project-ids set is loaded from the database exactly once and every subsequent
    // get is served from the cache, returning the identical set on every call (including an empty
    // set for a user with no memberships).
    /**
     * <b>Validates: Requirements 4.1, 4.2, 4.3</b>
     */
    @Property(tries = 100)
    void loadThenHitServesWithoutReloading(
            @ForAll("userIds") long userId,
            @ForAll("projectIdSets") Set<Long> allowedProjectIds,
            @ForAll @IntRange(min = 1, max = 50) int repetitions) {

        AtomicInteger loads = new AtomicInteger();
        ProjectMemberDao projectMemberDao = countingDao(userId, allowedProjectIds, loads);
        ProjectAccessCache cache = new ProjectAccessCache(freshProperties(), projectMemberDao);

        Set<Long> first = cache.get(userId);
        for (int i = 1; i < repetitions; i++) {
            Set<Long> subsequent = cache.get(userId);
            assertThat(subsequent)
                    .as("get #%d for user %d must return the identical cached set", i + 1, userId)
                    .isEqualTo(first)
                    .isEqualTo(allowedProjectIds);
        }

        assertThat(first)
                .as("the first get for user %d must return the loaded allowed-project-ids set", userId)
                .isEqualTo(allowedProjectIds);

        assertThat(loads.get())
                .as("user %d fetched %d times must load from the database exactly once; "
                        + "all subsequent gets are cache hits", userId, repetitions)
                .isEqualTo(1);
    }

    // --- Helpers ---

    /** A fresh, production-configured project-access cache properties (default 1440-minute idle TTL). */
    private ProjectAccessProperties freshProperties() {
        return new ProjectAccessProperties(null);
    }

    /**
     * A counting/spy {@link ProjectMemberDao} whose {@code findDistinctProjectIdsByUserId(userId)}
     * increments {@code loads} and returns {@code allowedProjectIds}. Any other user id resolves to
     * an empty set; the load counter measures how many times the cache's self-load path hit the DB.
     */
    private ProjectMemberDao countingDao(long userId, Set<Long> allowedProjectIds, AtomicInteger loads) {
        ProjectMemberDao dao = mock(ProjectMemberDao.class);
        when(dao.findDistinctProjectIdsByUserId(eq(userId))).thenAnswer(invocation -> {
            loads.incrementAndGet();
            return new HashSet<>(allowedProjectIds);
        });
        return dao;
    }

    // --- Arbitrary Providers ---

    /** Positive user ids, matching the numeric principal ids the JWT layer sets. */
    @Provide
    Arbitrary<Long> userIds() {
        return Arbitraries.longs().between(1L, 1_000_000L);
    }

    /**
     * Allowed-project-id sets, including the empty set (a user with no memberships) so the
     * empty-set load-then-hit case is exercised alongside non-empty sets (Req 4.3).
     */
    @Provide
    Arbitrary<Set<Long>> projectIdSets() {
        return Arbitraries.longs().between(1L, 10_000L)
                .set()
                .ofMinSize(0)
                .ofMaxSize(10);
    }
}
