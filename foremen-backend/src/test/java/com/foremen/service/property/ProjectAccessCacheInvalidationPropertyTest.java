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
import net.jqwik.api.constraints.LongRange;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property test for {@link ProjectAccessCache}'s invalidation round-trip.
 *
 * <p>Covers the design property assigned to task 3.3:</p>
 * <ul>
 *   <li><b>Property 4: Invalidation forces a reload (invalidation round-trip)</b>
 *       &mdash; Validates Requirements 8.1, 8.2, 8.3, 8.4, 8.5</li>
 * </ul>
 *
 * <p>The cache is exercised as the REAL {@link ProjectAccessCache} (constructed from
 * {@link ProjectAccessProperties} exactly as in production), so the caching semantics under test
 * are the production ones. The database load is counted through a counting/spy
 * {@link ProjectMemberDao} (a Mockito mock whose {@code findDistinctProjectIdsByUserId}
 * increments a load counter and returns whatever set is "current" at load time). Because the
 * spy's returned set can CHANGE between loads, the test proves not only that an invalidation
 * triggers exactly one extra load, but that the reloaded value reflects the current rows rather
 * than the previously cached snapshot (Requirements 8.1&ndash;8.5).</p>
 */
class ProjectAccessCacheInvalidationPropertyTest {

    // Feature: FOR-03-04-project-ownership, Property 4: Invalidation forces a reload (invalidation round-trip).
    // For any user id, an initial get(userId) caches the current rows with a single DB load; after
    // invalidate(userId) the next get(userId) reloads from the DAO (a second load) and reflects the
    // rows current at reload time, while further gets are served from the cache without reloading.
    /**
     * <b>Validates: Requirements 8.1, 8.2, 8.3, 8.4, 8.5</b>
     */
    @Property(tries = 100)
    void invalidationForcesReloadReflectingCurrentRows(
            @ForAll @LongRange(min = 1L, max = 1_000_000L) long userId,
            @ForAll("projectIdSets") Set<Long> initialRows,
            @ForAll("projectIdSets") Set<Long> updatedRows,
            @ForAll @IntRange(min = 1, max = 20) int preInvalidationReads,
            @ForAll @IntRange(min = 1, max = 20) int postReloadReads) {

        AtomicInteger loads = new AtomicInteger();
        // The "current rows" the DAO would return, mutated between loads to prove the reload
        // reflects the live rows rather than the previously cached snapshot.
        AtomicReference<Set<Long>> currentRows = new AtomicReference<>(initialRows);
        ProjectMemberDao dao = countingDao(userId, loads, currentRows);
        ProjectAccessCache cache = new ProjectAccessCache(freshProperties(), dao);

        // First access loads the initial rows from the DAO exactly once (Req 8.3 baseline).
        Set<Long> firstValue = cache.get(userId);
        assertThat(firstValue)
                .as("first get(%d) must reflect the initial rows", userId)
                .isEqualTo(initialRows);

        // Repeated reads before invalidation share that single load (cache hits).
        for (int i = 0; i < preInvalidationReads; i++) {
            assertThat(cache.get(userId))
                    .as("reads before invalidation must serve the cached initial rows")
                    .isEqualTo(initialRows);
        }
        assertThat(loads.get())
                .as("all reads before invalidation must share a single DB load")
                .isEqualTo(1);

        // The underlying rows change (a membership assign/remove or role change would do this),
        // then the cache entry is explicitly evicted (Req 8.1, 8.2, 8.4, 8.5).
        currentRows.set(updatedRows);
        cache.invalidate(userId);

        // The next get must reload from the DAO (Req 8.3) and reflect the CURRENT rows.
        Set<Long> reloaded = cache.get(userId);
        assertThat(loads.get())
                .as("get after invalidating user %d must reload from the DAO", userId)
                .isEqualTo(2);
        assertThat(reloaded)
                .as("the reloaded value must reflect the current rows, not the cached snapshot")
                .isEqualTo(updatedRows);

        // Further reads after the reload are cache hits again (no additional loads).
        for (int i = 0; i < postReloadReads; i++) {
            assertThat(cache.get(userId))
                    .as("reads after the post-invalidation reload must serve the cached current rows")
                    .isEqualTo(updatedRows);
        }
        assertThat(loads.get())
                .as("reads after the post-invalidation reload must not trigger further DB loads")
                .isEqualTo(2);
    }

    // --- Helpers ---

    /** A fresh, production-configured project-access cache (default 1440-minute idle TTL). */
    private ProjectAccessProperties freshProperties() {
        return new ProjectAccessProperties(null);
    }

    /**
     * A counting/spy {@link ProjectMemberDao} whose
     * {@code findDistinctProjectIdsByUserId(userId)} increments {@code loads} and returns a fresh
     * copy of whatever set {@code currentRows} holds at load time. Returning the live set snapshot
     * lets the test prove a reload reflects the current rows. Any other user id resolves to an
     * empty set; the counter measures how many times the cache's load path hit the database.
     */
    private ProjectMemberDao countingDao(long userId, AtomicInteger loads,
                                         AtomicReference<Set<Long>> currentRows) {
        ProjectMemberDao dao = mock(ProjectMemberDao.class);
        when(dao.findDistinctProjectIdsByUserId(eq(userId))).thenAnswer(invocation -> {
            loads.incrementAndGet();
            return new LinkedHashSet<>(currentRows.get());
        });
        return dao;
    }

    // --- Arbitrary Providers ---

    /**
     * Sets of project ids, including the empty set (a user with no memberships), used both as the
     * initial cached rows and as the post-invalidation rows so the two loads can legitimately
     * differ.
     */
    @Provide
    Arbitrary<Set<Long>> projectIdSets() {
        Arbitrary<Long> projectIds = Arbitraries.longs().between(1L, 10_000L);
        return projectIds.set().ofMinSize(0).ofMaxSize(8)
                .map(LinkedHashSet::new);
    }
}
