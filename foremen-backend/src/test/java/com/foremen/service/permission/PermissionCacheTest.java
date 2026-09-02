package com.foremen.service.permission;

import com.foremen.config.security.PermissionProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Edge-case and smoke tests for the dedicated permission cache and its configuration.
 *
 * <p>Covers task 2.3 of FOR-03-03:
 * <ul>
 *   <li><b>Idle expiry (Requirement 8.4)</b> — using a controllable Caffeine {@link Ticker},
 *       an entry survives while it keeps being read but drops once idle beyond the idle TTL.</li>
 *   <li><b>Capacity holds every role (Requirement 8.3)</b> — the cache retains an entry for
 *       every seeded role code (and well beyond) without capacity eviction.</li>
 *   <li><b>Dedicated instance (Requirements 8.3, 8.4)</b> — the cache is a per-instance
 *       standalone Caffeine {@link Cache}, not a view derived from the global cache spec /
 *       Spring {@code CacheManager}; two instances are fully independent.</li>
 *   <li><b>Default idle TTL is 1440 minutes (Requirement 8.4)</b> — asserted through
 *       {@link PermissionProperties}.</li>
 * </ul>
 *
 * <p>The production {@link PermissionCache} builds its Caffeine cache in its constructor with the
 * system-clock ticker and exposes no ticker seam, so the idle-expiry test builds a standalone
 * cache mirroring the production configuration (same {@code expireAfterAccess(cacheTtlMinutes)}
 * derived from {@link PermissionProperties}) driven by a fake ticker. This asserts the
 * configured retention semantics without modifying production code. Capacity and
 * dedicated-instance behaviour are exercised through the real {@link PermissionCache} public API.
 */
@DisplayName("PermissionCache edge-case and smoke tests")
class PermissionCacheTest {

    /** The six seeded role codes (see changeset 004/006); the cache must hold all of them. */
    private static final List<String> SEEDED_ROLE_CODES =
            List.of("ADMIN", "MANAGER", "FOREMAN", "WORKER", "FINANCIER", "CLIENT");

    /**
     * A controllable Caffeine {@link Ticker} whose logical time only advances when told to,
     * so idle-expiry can be asserted deterministically without real waiting.
     */
    private static final class ControllableTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong(0);

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }

    // --- Requirement 8.4: idle expiry (expire-after-access) ---

    @Nested
    @DisplayName("idle expiry (Requirement 8.4)")
    class IdleExpiry {

        /**
         * Builds a cache configured exactly like the production {@link PermissionCache}
         * (idle TTL from {@link PermissionProperties}, large capacity) but driven by the
         * supplied fake ticker so idle time can be advanced deterministically.
         */
        private Cache<String, PermissionSet> mirrorCache(ControllableTicker ticker, int ttlMinutes) {
            return Caffeine.newBuilder()
                    .expireAfterAccess(Duration.ofMinutes(ttlMinutes))
                    .maximumSize(1_000)
                    .ticker(ticker)
                    .build();
        }

        @Test
        @DisplayName("evicts an entry once it has been idle beyond the idle TTL")
        void entryExpiresAfterIdleTtl() {
            ControllableTicker ticker = new ControllableTicker();
            int ttlMinutes = new PermissionProperties(null).cacheTtlMinutes(); // 1440
            Cache<String, PermissionSet> cache = mirrorCache(ticker, ttlMinutes);

            cache.put("MANAGER", new PermissionSet(Set.of("PROJECTS:READ")));

            // Well within the idle TTL the entry is still present. A generous margin (half the TTL)
            // avoids Caffeine's coarse timer-wheel resolution producing a false early expiry.
            ticker.advance(Duration.ofMinutes(ttlMinutes / 2));
            cache.cleanUp();
            assertThat(cache.getIfPresent("MANAGER"))
                    .as("entry must survive while idle time is below the idle TTL")
                    .isNotNull();

            // getIfPresent above counts as an access and resets the idle timer; now go idle
            // well beyond the TTL without any read -> entry expires.
            ticker.advance(Duration.ofMinutes(ttlMinutes).plusMinutes(ttlMinutes / 2));
            cache.cleanUp();
            assertThat(cache.getIfPresent("MANAGER"))
                    .as("entry must expire once idle beyond the idle TTL")
                    .isNull();
        }

        @Test
        @DisplayName("keeps an entry alive as long as it is read within the idle TTL (expire-after-access, not after-write)")
        void readWithinTtlKeepsEntryAlive() {
            ControllableTicker ticker = new ControllableTicker();
            int ttlMinutes = new PermissionProperties(null).cacheTtlMinutes();
            Cache<String, PermissionSet> cache = mirrorCache(ticker, ttlMinutes);

            cache.put("FOREMAN", new PermissionSet(Set.of("PROJECTS:READ")));

            // Repeatedly read within the idle window; each access resets the idle timer, so the
            // entry survives arbitrarily long as long as it keeps being read (expire-after-access).
            for (int i = 0; i < 5; i++) {
                ticker.advance(Duration.ofMinutes(ttlMinutes / 2));
                cache.cleanUp();
                assertThat(cache.getIfPresent("FOREMAN"))
                        .as("read within the idle window must keep the entry alive (round %d)", i)
                        .isNotNull();
            }

            // Now go idle well past the TTL without reading -> it finally expires.
            ticker.advance(Duration.ofMinutes(ttlMinutes).plusMinutes(ttlMinutes / 2));
            cache.cleanUp();
            assertThat(cache.getIfPresent("FOREMAN"))
                    .as("entry expires only after being idle beyond the idle TTL")
                    .isNull();
        }
    }

    // --- Requirement 8.3: capacity holds every role ---

    @Nested
    @DisplayName("capacity (Requirement 8.3)")
    class Capacity {

        @Test
        @DisplayName("retains an entry for every seeded role code without capacity eviction")
        void holdsEverySeededRole() {
            PermissionCache cache = new PermissionCache(new PermissionProperties(null));
            AtomicInteger loads = new AtomicInteger();

            // Prime the cache for every seeded role.
            for (String roleCode : SEEDED_ROLE_CODES) {
                cache.get(roleCode, code -> {
                    loads.incrementAndGet();
                    return new PermissionSet(Set.of(PermissionSet.key("PROJECTS", "READ")));
                });
            }
            assertThat(loads.get())
                    .as("initial priming must load each seeded role exactly once")
                    .isEqualTo(SEEDED_ROLE_CODES.size());

            // A second pass must all be cache hits (loader never invoked again).
            for (String roleCode : SEEDED_ROLE_CODES) {
                cache.get(roleCode, code -> {
                    loads.incrementAndGet();
                    return PermissionSet.empty();
                });
            }
            assertThat(loads.get())
                    .as("no seeded role must be evicted for capacity; every re-read is a cache hit")
                    .isEqualTo(SEEDED_ROLE_CODES.size());
        }

        @Test
        @DisplayName("retains far more entries than the number of roles without capacity eviction")
        void holdsWellBeyondRoleCount() {
            PermissionCache cache = new PermissionCache(new PermissionProperties(null));
            AtomicInteger loads = new AtomicInteger();

            int entries = 500; // comfortably above any realistic role count, below maximumSize
            for (int i = 0; i < entries; i++) {
                String roleCode = "ROLE_" + i;
                cache.get(roleCode, code -> {
                    loads.incrementAndGet();
                    return PermissionSet.empty();
                });
            }
            assertThat(loads.get()).isEqualTo(entries);

            // Re-read every key: all must be hits, proving none were evicted for capacity.
            for (int i = 0; i < entries; i++) {
                String roleCode = "ROLE_" + i;
                cache.get(roleCode, code -> {
                    loads.incrementAndGet();
                    return PermissionSet.empty();
                });
            }
            assertThat(loads.get())
                    .as("cache sized to hold every role must not evict any of %d entries", entries)
                    .isEqualTo(entries);
        }
    }

    // --- Requirements 8.3 / 8.4: dedicated, per-instance Caffeine cache ---

    @Nested
    @DisplayName("dedicated instance (Requirements 8.3, 8.4)")
    class DedicatedInstance {

        @Test
        @DisplayName("backs onto a standalone Caffeine cache, not a Spring CacheManager view")
        void backedByStandaloneCaffeineCache() throws Exception {
            PermissionCache cache = new PermissionCache(new PermissionProperties(null));

            Field field = PermissionCache.class.getDeclaredField("cache");
            field.setAccessible(true);
            Object backing = field.get(cache);

            assertThat(backing)
                    .as("the permission cache must be a dedicated Caffeine Cache, "
                            + "independent of the global cache spec / Spring CacheManager")
                    .isInstanceOf(Cache.class);
            assertThat(backing.getClass().getName())
                    .as("backing cache must come from the Caffeine package")
                    .startsWith("com.github.benmanes.caffeine.cache");
        }

        @Test
        @DisplayName("two instances are fully independent (not sharing a global cache)")
        void instancesAreIndependent() {
            PermissionCache first = new PermissionCache(new PermissionProperties(null));
            PermissionCache second = new PermissionCache(new PermissionProperties(null));

            AtomicInteger firstLoads = new AtomicInteger();
            AtomicInteger secondLoads = new AtomicInteger();

            first.get("MANAGER", code -> {
                firstLoads.incrementAndGet();
                return new PermissionSet(Set.of("PROJECTS:READ"));
            });

            // The second instance must not see the first instance's entry.
            second.get("MANAGER", code -> {
                secondLoads.incrementAndGet();
                return PermissionSet.empty();
            });

            assertThat(firstLoads.get()).as("first cache loads once").isEqualTo(1);
            assertThat(secondLoads.get())
                    .as("second cache is a separate instance and must load independently")
                    .isEqualTo(1);

            // Invalidating one must not affect the other.
            first.invalidate("MANAGER");
            second.get("MANAGER", code -> {
                secondLoads.incrementAndGet();
                return PermissionSet.empty();
            });
            assertThat(secondLoads.get())
                    .as("invalidating the first cache must not evict the second cache's entry")
                    .isEqualTo(1);
        }
    }

    // --- Requirement 8.4: default idle TTL is 1440 minutes ---

    @Nested
    @DisplayName("default idle TTL (Requirement 8.4)")
    class DefaultIdleTtl {

        @Test
        @DisplayName("defaults the idle TTL to 1440 minutes (24 hours) when unset")
        void defaultsTo1440Minutes() {
            assertThat(new PermissionProperties(null).cacheTtlMinutes())
                    .as("idle TTL must default to 1440 minutes (24 hours)")
                    .isEqualTo(1440);
        }

        @Test
        @DisplayName("honours an explicitly configured idle TTL")
        void honoursExplicitValue() {
            assertThat(new PermissionProperties(30).cacheTtlMinutes()).isEqualTo(30);
        }
    }
}
