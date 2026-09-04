package com.foremen.service.property;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.foremen.config.security.JwtClaims;
import com.foremen.config.security.JwtProperties;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.dao.RefreshTokenDao;
import com.foremen.dao.model.RefreshTokenEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.RefreshTokenService;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based test for FOR-03-05-otp-client-auth, Property 13: client TTL selection does
 * not affect employee flows (Requirements 7.3, 7.4).
 *
 * <p>The design chooses the token lifetime by the <em>caller path</em>: the OTP verify path
 * passes the client TTLs to the TTL-aware {@link JwtTokenProvider#generateAccessToken(Long,
 * String, String, int)} and {@link RefreshTokenService#issue(UserEntity, int)} overloads,
 * while the employee login/refresh paths keep calling the employee-TTL methods
 * ({@link JwtTokenProvider#generateAccessToken(Long, String, String)} and
 * {@link RefreshTokenService#issue(UserEntity)}). This test exercises both paths over
 * generated employee and client TTL configurations and asserts each issued expiry matches
 * its own TTL within a &plusmn;2 second tolerance, so the client TTLs never bleed into the
 * employee flows and vice versa.
 *
 * <p>The real {@link JwtTokenProvider} is used and its access-token expiry is read back
 * through {@link JwtTokenProvider#validate(String)}. The real {@link RefreshTokenService} is
 * wired over an in-memory, map-backed {@link RefreshTokenDao} mock (mirroring
 * {@link RefreshTokenServicePropertyTest}) so the persisted refresh-token expiry can be
 * inspected without a database.
 *
 * Property 13: Client TTL selection does not affect employee flows — Validates: Requirements 7.3, 7.4
 */
@Tag("Feature: FOR-03-05-otp-client-auth, Property 13: Client TTL selection vs employee flows")
class ClientTtlSelectionPropertyTest {

    private static final String SECRET =
            "foremen-jwt-test-secret-key-0123456789ABCDEF";

    /** Second-precision tolerance for expiry comparisons (JWT expiry is second-precision). */
    private static final long TOLERANCE_SECONDS = 2;

    /**
     * A tiny in-memory store backing the mocked {@link RefreshTokenDao}, assigning sequential
     * ids on save and keeping entities by id — enough for the service under test.
     */
    private static final class InMemoryStore {
        private final Map<Long, RefreshTokenEntity> byId = new ConcurrentHashMap<>();
        private final AtomicLong sequence = new AtomicLong(0);

        RefreshTokenEntity save(RefreshTokenEntity entity) {
            if (entity.getId() == null) {
                entity.setId(sequence.incrementAndGet());
            }
            byId.put(entity.getId(), entity);
            return entity;
        }

        Optional<RefreshTokenEntity> findByToken(String token) {
            return byId.values().stream()
                    .filter(e -> e.getToken().equals(token))
                    .findFirst();
        }
    }

    private record Fixture(JwtTokenProvider provider, RefreshTokenService refreshService, InMemoryStore store) {}

    /** Builds a provider + refresh service over the given employee/client TTL configuration. */
    private static Fixture fixture(int accessTtlMinutes, int refreshTtlDays,
                                   int clientAccessTtlMinutes, int clientRefreshTtlDays) {
        JwtProperties props = new JwtProperties(
                accessTtlMinutes, refreshTtlDays, clientAccessTtlMinutes, clientRefreshTtlDays, SECRET);

        JwtTokenProvider provider = new JwtTokenProvider(props);

        InMemoryStore store = new InMemoryStore();
        RefreshTokenDao dao = Mockito.mock(RefreshTokenDao.class);
        when(dao.save(any(RefreshTokenEntity.class)))
                .thenAnswer(inv -> store.save(inv.getArgument(0)));
        when(dao.findByToken(any()))
                .thenAnswer(inv -> store.findByToken(inv.getArgument(0)));

        RefreshTokenService refreshService = new RefreshTokenService(dao, props);
        return new Fixture(provider, refreshService, store);
    }

    private static UserEntity user(long id, String roleCode) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setName("user-" + id);
        user.setEmail("user" + id + "@example.com");
        RoleEntity role = new RoleEntity();
        role.setId(id);
        role.setCode(roleCode);
        user.setRole(role);
        return user;
    }

    // Feature: FOR-03-05-otp-client-auth, Property 13: Client TTL selection does not affect employee flows
    // For all configured employee TTLs and client TTLs, tokens issued through the OTP verify
    // path (TTL-aware overloads) expire at issuance + the client TTLs, while tokens issued
    // through the employee login/refresh path (zero-TTL methods) expire at issuance + the
    // employee TTLs — each within ±2 seconds, regardless of the other configuration.
    // Validates: Requirements 7.3, 7.4
    @Property(tries = 100)
    void clientTtlDoesNotAffectEmployeeFlows(
            @ForAll("ttlMinutes") int accessTtlMinutes,
            @ForAll("ttlDays") int refreshTtlDays,
            @ForAll("ttlMinutes") int clientAccessTtlMinutes,
            @ForAll("ttlDays") int clientRefreshTtlDays,
            @ForAll("userIds") long userId) {

        Fixture fx = fixture(accessTtlMinutes, refreshTtlDays, clientAccessTtlMinutes, clientRefreshTtlDays);

        UserEntity employee = user(userId, "WORKER");
        UserEntity client = user(userId + 1, "CLIENT");

        // --- Employee flow (FOR-03-01 login/refresh): zero-TTL methods → employee TTLs (7.4) ---
        Instant beforeEmp = Instant.now();
        String empAccess = fx.provider().generateAccessToken(
                employee.getId(), employee.getRole().getCode(), employee.getEmail());
        String empRefresh = fx.refreshService().issue(employee);
        Instant afterEmp = Instant.now();

        assertExpiryWithin(accessExpiry(fx.provider(), empAccess), beforeEmp, afterEmp,
                Duration.ofMinutes(accessTtlMinutes), "employee access token uses the employee access TTL");
        assertExpiryWithin(refreshExpiry(fx.store(), empRefresh), beforeEmp, afterEmp,
                Duration.ofDays(refreshTtlDays), "employee refresh token uses the employee refresh TTL");

        // --- Client flow (OTP verify): TTL-aware overloads → client TTLs (7.3) ---
        Instant beforeCli = Instant.now();
        String cliAccess = fx.provider().generateAccessToken(
                client.getId(), client.getRole().getCode(), client.getEmail(), clientAccessTtlMinutes);
        String cliRefresh = fx.refreshService().issue(client, clientRefreshTtlDays);
        Instant afterCli = Instant.now();

        assertExpiryWithin(accessExpiry(fx.provider(), cliAccess), beforeCli, afterCli,
                Duration.ofMinutes(clientAccessTtlMinutes), "client access token uses the client access TTL");
        assertExpiryWithin(refreshExpiry(fx.store(), cliRefresh), beforeCli, afterCli,
                Duration.ofDays(clientRefreshTtlDays), "client refresh token uses the client refresh TTL");
    }

    /** Reads the access-token expiry back through the provider's own validation. */
    private static Instant accessExpiry(JwtTokenProvider provider, String token) {
        JwtClaims claims = provider.validate(token)
                .orElseThrow(() -> new AssertionError("a freshly generated access token must be valid"));
        return claims.expiresAt();
    }

    /** Reads the persisted refresh-token expiry from the in-memory store. */
    private static Instant refreshExpiry(InMemoryStore store, String token) {
        return store.findByToken(token)
                .orElseThrow(() -> new AssertionError("an issued refresh token must be persisted"))
                .getExpiresAt();
    }

    /**
     * Asserts the expiry falls within {@code [before + ttl - tol, after + ttl + tol]}. Bounds
     * are truncated to seconds because JWT expiry is second-precision.
     */
    private static void assertExpiryWithin(Instant expiry, Instant before, Instant after,
                                           Duration ttl, String description) {
        Instant lower = before.plus(ttl).minusSeconds(TOLERANCE_SECONDS).truncatedTo(ChronoUnit.SECONDS);
        Instant upper = after.plus(ttl).plusSeconds(TOLERANCE_SECONDS);
        assertThat(expiry)
                .as(description)
                .isAfterOrEqualTo(lower.minusSeconds(1))
                .isBeforeOrEqualTo(upper.plusSeconds(1));
    }

    // --- Providers ---

    /** Access-token lifetimes in minutes (bounded to keep expiry arithmetic well within range). */
    @Provide
    Arbitrary<Integer> ttlMinutes() {
        return Arbitraries.integers().between(1, 1440);
    }

    /** Refresh-token lifetimes in days. */
    @Provide
    Arbitrary<Integer> ttlDays() {
        return Arbitraries.integers().between(1, 365);
    }

    /** User ids: positive longs, bounded so {@code userId + 1} stays positive. */
    @Provide
    Arbitrary<Long> userIds() {
        return Arbitraries.longs().between(1L, Long.MAX_VALUE - 1);
    }
}
