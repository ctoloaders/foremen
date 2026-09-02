package com.foremen.service.permission;

import com.foremen.config.security.PermissionProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Function;

/**
 * Dedicated, long-lived Caffeine cache mapping a role code to its {@link PermissionSet}.
 *
 * <p>This cache is built independently of the global {@code foremen.cache.spec} {@code CacheManager}:
 * it is sized to hold every role ({@code maximumSize} well above the role count, so no role's
 * permission set is evicted for capacity reasons) and uses only an idle expiry
 * ({@code expireAfterAccess}) as a safety net for a missed invalidation. There is no
 * write-based expiry — a role's matrix changes rarely and every change explicitly invalidates
 * the affected entry, so explicit {@link #invalidate(String)} is the authoritative freshness
 * mechanism (Requirements 8.1–8.4, 9.1).
 */
@Component
public class PermissionCache {

    private final Cache<String, PermissionSet> cache;

    public PermissionCache(PermissionProperties properties) {
        this.cache = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(properties.cacheTtlMinutes()))
                .maximumSize(1_000)
                .build();
    }

    /**
     * Returns the cached {@link PermissionSet} for {@code roleCode}, loading and storing it via
     * {@code loader} on a cache miss. On a hit the value is served without invoking the loader.
     */
    public PermissionSet get(String roleCode, Function<String, PermissionSet> loader) {
        return cache.get(roleCode, loader);
    }

    /**
     * Evicts the cache entry for {@code roleCode} so the next {@link #get} reloads it.
     */
    public void invalidate(String roleCode) {
        cache.invalidate(roleCode);
    }
}
