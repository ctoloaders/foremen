package com.foremen.service.permission.property;

import com.foremen.dao.RoleDao;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.config.security.PermissionProperties;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import com.foremen.service.permission.PermissionCache;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property tests for the {@link ForemenPermissionEvaluator}'s interaction with the
 * {@link PermissionCache}.
 *
 * <p>Covers the two design properties assigned to task 3.3:</p>
 * <ul>
 *   <li><b>Property 8: A cache hit serves without reloading</b>
 *       &mdash; Validates Requirements 8.2</li>
 *   <li><b>Property 9: Invalidation forces a reload (invalidation round-trip)</b>
 *       &mdash; Validates Requirements 9.1, 9.2</li>
 * </ul>
 *
 * <p>The evaluator is exercised against a REAL {@link PermissionCache} (constructed from
 * {@link PermissionProperties} exactly as in production) so the caching semantics under test are
 * the production ones. The database load is counted through a spy/counting {@link RoleDao}
 * (a Mockito mock whose {@code findByCode} increments a load counter), which lets us assert
 * exactly how many times the DB is consulted across repeated evaluations and after an
 * invalidation.</p>
 */
class PermissionCacheInteractionPropertyTest {

    // Feature: FOR-03-03-permission-evaluator, Property 8: A cache hit serves without reloading.
    // For any non-ADMIN role code and any N >= 1 repeated evaluations without an intervening
    // invalidation, the permission set is loaded from the database exactly once; every subsequent
    // evaluation is served from the cache.
    /**
     * <b>Validates: Requirements 8.2</b>
     */
    @Property(tries = 100)
    void cacheHitServesWithoutReloading(
            @ForAll("roleCodes") String roleCode,
            @ForAll("codes") String resource,
            @ForAll("codes") String operation,
            @ForAll @IntRange(min = 1, max = 50) int repetitions) {

        AtomicInteger loads = new AtomicInteger();
        RoleDao roleDao = countingRoleDao(roleCode, loads);
        ForemenPermissionEvaluator evaluator =
                new ForemenPermissionEvaluator(roleDao, freshCache());

        for (int i = 0; i < repetitions; i++) {
            evaluator.isAllowed(roleCode, resource, operation);
        }

        assertThat(loads.get())
                .as("role '%s' evaluated %d times must load from the database exactly once; "
                        + "all subsequent evaluations are cache hits", roleCode, repetitions)
                .isEqualTo(1);
    }

    // Feature: FOR-03-03-permission-evaluator, Property 9: Invalidation forces a reload (invalidation round-trip).
    // For any non-ADMIN role code, after an initial evaluation caches the set, invalidating that
    // role's entry causes the next evaluation to reload from the database (one load before, exactly
    // two loads after the invalidated re-evaluation).
    /**
     * <b>Validates: Requirements 9.1, 9.2</b>
     */
    @Property(tries = 100)
    void invalidationForcesReload(
            @ForAll("roleCodes") String roleCode,
            @ForAll("codes") String resource,
            @ForAll("codes") String operation,
            @ForAll @IntRange(min = 1, max = 20) int preInvalidationReads) {

        AtomicInteger loads = new AtomicInteger();
        RoleDao roleDao = countingRoleDao(roleCode, loads);
        PermissionCache cache = freshCache();
        ForemenPermissionEvaluator evaluator = new ForemenPermissionEvaluator(roleDao, cache);

        // Repeated evaluations before invalidation all share a single load.
        for (int i = 0; i < preInvalidationReads; i++) {
            evaluator.isAllowed(roleCode, resource, operation);
        }
        assertThat(loads.get())
                .as("evaluations before invalidation must share a single load")
                .isEqualTo(1);

        // Any RoleService touch point evicts the entry keyed by role code (9.1/9.2).
        cache.invalidate(roleCode);

        // The next evaluation must reload from the database.
        evaluator.isAllowed(roleCode, resource, operation);
        assertThat(loads.get())
                .as("evaluation after invalidating '%s' must reload from the database", roleCode)
                .isEqualTo(2);

        // Further evaluations after the reload are cache hits again.
        evaluator.isAllowed(roleCode, resource, operation);
        assertThat(loads.get())
                .as("evaluations after the post-invalidation reload must be cache hits")
                .isEqualTo(2);
    }

    // --- Helpers ---

    /** A fresh, production-configured dedicated permission cache (default 1440-minute idle TTL). */
    private PermissionCache freshCache() {
        return new PermissionCache(new PermissionProperties(null));
    }

    /**
     * A counting/spy {@link RoleDao} whose {@code findByCode(roleCode)} increments {@code loads}
     * and returns a role entity carrying a small permission matrix. Any other code resolves to
     * empty; the load counter measures how many times the evaluator's load path hit the database.
     */
    private RoleDao countingRoleDao(String roleCode, AtomicInteger loads) {
        RoleDao roleDao = mock(RoleDao.class);
        when(roleDao.findByCode(eq(roleCode))).thenAnswer(invocation -> {
            loads.incrementAndGet();
            return Optional.of(roleWith(roleCode, "PROJECTS", List.of("READ", "CREATE")));
        });
        return roleDao;
    }

    /** Builds a RoleEntity graph granting {@code operations} on {@code resourceCode}. */
    private RoleEntity roleWith(String code, String resourceCode, List<String> operations) {
        RoleEntity role = new RoleEntity();
        role.setCode(code);

        ResourceEntity resource = new ResourceEntity();
        resource.setCode(resourceCode);

        List<OperationEntity> ops = new ArrayList<>();
        for (String opCode : operations) {
            OperationEntity op = new OperationEntity();
            op.setCode(opCode);
            ops.add(op);
        }

        RoleResourceEntity rr = new RoleResourceEntity();
        rr.setResource(resource);
        rr.setOperations(ops);

        role.getRoleResources().add(rr);
        return role;
    }

    // --- Arbitrary Providers ---

    /** Non-ADMIN role codes (ADMIN is bypassed and never consults the cache/DB). */
    @Provide
    Arbitrary<String> roleCodes() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(12)
                .filter(code -> !ForemenPermissionEvaluator.ADMIN_ROLE_CODE.equals(code));
    }

    /** Resource/operation codes: non-blank alphabetic strings. */
    @Provide
    Arbitrary<String> codes() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(12);
    }
}
