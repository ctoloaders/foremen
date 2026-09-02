package com.foremen.service;

import com.foremen.config.security.ProjectAccessProperties;
import com.foremen.dao.ProjectMemberDao;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * Dedicated, long-lived Caffeine cache mapping a user id to that user's set of allowed
 * project ids ({@code userId -> Set<Long>}).
 *
 * <p>Built independently of the global {@code foremen.cache.spec} {@code CacheManager}, mirroring
 * {@link com.foremen.service.permission.PermissionCache}: it is sized generously
 * ({@code maximumSize(1_000)}) and uses only an idle expiry ({@code expireAfterAccess}) as a
 * safety net for a missed invalidation. There is no write-based expiry — a user's project
 * membership changes rarely and every change explicitly invalidates the affected entry, so
 * explicit {@link #invalidate(Long)} is the authoritative freshness mechanism
 * (Requirements 4.4, 4.5, 8.5).
 *
 * <p>Unlike {@code PermissionCache}, {@link #get(Long)} is self-loading: on a miss it loads the
 * user's allowed project ids from {@link ProjectMemberDao#findDistinctProjectIdsByUserId(Long)},
 * so callers do not supply a loader (Requirements 4.1, 4.2, 4.3).
 */
@Component
public class ProjectAccessCache {

    private final Cache<Long, Set<Long>> cache;
    private final ProjectMemberDao projectMemberDao;

    public ProjectAccessCache(ProjectAccessProperties properties, ProjectMemberDao projectMemberDao) {
        this.projectMemberDao = projectMemberDao;
        this.cache = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(properties.cacheTtlMinutes()))
                .maximumSize(1_000)
                .build();
    }

    /**
     * Returns the user's allowed project ids, self-loading from the database via
     * {@link ProjectMemberDao#findDistinctProjectIdsByUserId(Long)} on a cache miss and storing
     * the result keyed by {@code userId}. On a hit the value is served without querying the
     * database. A user with no memberships resolves to an empty set (Requirements 4.1, 4.2, 4.3).
     */
    public Set<Long> get(Long userId) {
        return cache.get(userId, projectMemberDao::findDistinctProjectIdsByUserId);
    }

    /**
     * Evicts the cache entry for {@code userId} so the next {@link #get(Long)} reloads it from the
     * database (Requirements 8.1, 8.2, 8.5).
     */
    public void invalidate(Long userId) {
        cache.invalidate(userId);
    }
}
