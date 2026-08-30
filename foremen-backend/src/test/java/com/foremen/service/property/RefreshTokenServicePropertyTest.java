package com.foremen.service.property;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.foremen.config.security.JwtProperties;
import com.foremen.dao.RefreshTokenDao;
import com.foremen.dao.model.RefreshTokenEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.RefreshTokenService;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for {@link RefreshTokenService} (Requirements 7, 8, 14.4).
 *
 * <p>Each test wires a fresh {@link RefreshTokenService} over an in-memory, map-backed
 * {@link RefreshTokenDao} (a Mockito mock whose {@code save}/{@code saveAll}/{@code findByToken}/
 * {@code findByUserIdAndRevokedFalse} answers are delegated to an in-memory store). The store
 * assigns ids and honours the {@code token}-uniqueness the service relies on, so persistence
 * behaviour is faithful without a database. {@link JwtProperties} is constructed directly.
 *
 * <p>The service has no dedicated {@code logout} method: logout is modelled by {@code revoke}
 * (idempotent), matching the AuthService design where {@code logout} delegates to
 * {@code RefreshTokenService.revoke}.
 *
 * Property 15: Refresh-token issuance invariant — Validates: Requirements 7.1, 14.4
 * Property 16: Refresh rotation is one-time-use — Validates: Requirements 7.3, 7.7, 8.4
 * Property 17: Unknown refresh token is rejected — Validates: Requirements 7.4
 * Property 18: Revoked refresh token is rejected — Validates: Requirements 7.5
 * Property 19: Expired refresh token is rejected — Validates: Requirements 7.6
 * Property 20: Logout revokes a known refresh token — Validates: Requirements 8.2
 * Property 21: Logout is idempotent for unknown tokens — Validates: Requirements 8.3
 */
@Tag("Feature: FOR-03-01-jwt-auth, Property 15-21: RefreshTokenService")
class RefreshTokenServicePropertyTest {

    private static final String SECRET =
            "foremen-jwt-test-secret-key-0123456789ABCDEF";

    /**
     * A tiny in-memory store backing the mocked {@link RefreshTokenDao}. It assigns sequential
     * ids on save and keeps entities keyed by id, mirroring JPA identity semantics closely
     * enough for the service under test.
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

        List<RefreshTokenEntity> saveAll(Iterable<RefreshTokenEntity> entities) {
            List<RefreshTokenEntity> saved = new ArrayList<>();
            for (RefreshTokenEntity e : entities) {
                saved.add(save(e));
            }
            return saved;
        }

        Optional<RefreshTokenEntity> findByToken(String token) {
            return byId.values().stream()
                    .filter(e -> e.getToken().equals(token))
                    .findFirst();
        }

        List<RefreshTokenEntity> findByUserIdAndRevokedFalse(Long userId) {
            return byId.values().stream()
                    .filter(e -> !e.isRevoked())
                    .filter(e -> e.getUser() != null
                            && e.getUser().getId() != null
                            && e.getUser().getId().equals(userId))
                    .toList();
        }
    }

    /**
     * Builds a service backed by a fresh in-memory store. Returns both so a test can inspect
     * the persisted state directly.
     */
    private static Fixture fixture(int refreshTtlDays) {
        InMemoryStore store = new InMemoryStore();
        RefreshTokenDao dao = Mockito.mock(RefreshTokenDao.class);

        when(dao.save(any(RefreshTokenEntity.class)))
                .thenAnswer(inv -> store.save(inv.getArgument(0)));
        when(dao.saveAll(any()))
                .thenAnswer(inv -> store.saveAll(inv.getArgument(0)));
        when(dao.findByToken(any()))
                .thenAnswer(inv -> store.findByToken(inv.getArgument(0)));
        when(dao.findByUserIdAndRevokedFalse(any()))
                .thenAnswer(inv -> store.findByUserIdAndRevokedFalse(inv.getArgument(0)));

        JwtProperties props = new JwtProperties(30, refreshTtlDays, SECRET);
        RefreshTokenService service = new RefreshTokenService(dao, props);
        return new Fixture(service, store);
    }

    private record Fixture(RefreshTokenService service, InMemoryStore store) {}

    private static UserEntity user(long id) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setName("user-" + id);
        user.setEmail("user" + id + "@example.com");
        RoleEntity role = new RoleEntity();
        role.setId(id);
        role.setCode("WORKER");
        user.setRole(role);
        return user;
    }

    // Feature: FOR-03-01-jwt-auth, Property 15: Refresh-token issuance invariant
    // For all users and positive refresh lifetimes (days), issuing a refresh token persists an
    // entity owned by that user with revoked=false and an expiry within a small tolerance of
    // issuance plus the lifetime.
    // Validates: Requirements 7.1, 14.4
    @Property(tries = 100)
    void issuanceInvariant(@ForAll("userIds") long userId,
                           @ForAll @IntRange(min = 1, max = 365) int refreshTtlDays) {
        Fixture fx = fixture(refreshTtlDays);
        UserEntity user = user(userId);

        Instant before = Instant.now();
        String token = fx.service().issue(user);
        Instant after = Instant.now();

        assertThat(token).as("issued token value must be non-blank").isNotBlank();

        RefreshTokenEntity persisted = fx.store().findByToken(token)
                .orElseThrow(() -> new AssertionError("issued token must be persisted"));

        assertThat(persisted.isRevoked()).as("newly issued token must not be revoked").isFalse();
        assertThat(persisted.getUser()).as("token must be owned by the issuing user").isEqualTo(user);

        Instant lower = before.plus(Duration.ofDays(refreshTtlDays)).minusSeconds(2);
        Instant upper = after.plus(Duration.ofDays(refreshTtlDays)).plusSeconds(2);
        assertThat(persisted.getExpiresAt())
                .as("expiry must be within tolerance of issuance + ttl days")
                .isAfterOrEqualTo(lower)
                .isBeforeOrEqualTo(upper);
    }

    // Feature: FOR-03-01-jwt-auth, Property 16: Refresh rotation is one-time-use
    // For all active refresh tokens, a successful rotate marks the supplied token revoked and
    // causes any subsequent rotate using that same token (re-presented, or previously revoked
    // via logout) to fail.
    // Validates: Requirements 7.3, 7.7, 8.4
    @Property(tries = 100)
    void rotationIsOneTimeUse(@ForAll("userIds") long userId,
                              @ForAll boolean viaLogout) {
        Fixture fx = fixture(7);
        UserEntity user = user(userId);
        String token = fx.service().issue(user);

        if (viaLogout) {
            // Model logout-then-refresh: the token is revoked by logout, subsequent refresh fails.
            fx.service().revoke(token);
            assertThatThrownBy(() -> fx.service().rotate(token))
                    .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                        assertThat(ex.getMessageCode()).isEqualTo("error.auth.refresh.revoked");
                    });
        } else {
            // Model refresh rotation: first rotate succeeds and revokes the old token.
            RefreshTokenEntity rotated = fx.service().rotate(token);
            assertThat(rotated.isRevoked()).as("rotated token must be marked revoked").isTrue();

            // Re-presenting the same token must now fail (one-time-use).
            assertThatThrownBy(() -> fx.service().rotate(token))
                    .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                        assertThat(ex.getMessageCode()).isEqualTo("error.auth.refresh.revoked");
                    });
        }
    }

    // Feature: FOR-03-01-jwt-auth, Property 17: Unknown refresh token is rejected
    // For all refresh-token values matching no persisted entity, rotate raises a 401 with
    // message code error.auth.refresh.invalid.
    // Validates: Requirements 7.4
    @Property(tries = 100)
    void unknownTokenRejected(@ForAll("tokenValues") String unknownToken) {
        Fixture fx = fixture(7);

        assertThatThrownBy(() -> fx.service().rotate(unknownToken))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getMessageCode()).isEqualTo("error.auth.refresh.invalid");
                });
    }

    // Feature: FOR-03-01-jwt-auth, Property 18: Revoked refresh token is rejected
    // For all persisted refresh tokens whose revoked value is true, rotate raises a 401 with
    // message code error.auth.refresh.revoked.
    // Validates: Requirements 7.5
    @Property(tries = 100)
    void revokedTokenRejected(@ForAll("userIds") long userId) {
        Fixture fx = fixture(7);
        UserEntity user = user(userId);
        String token = fx.service().issue(user);
        fx.service().revoke(token);

        assertThatThrownBy(() -> fx.service().rotate(token))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getMessageCode()).isEqualTo("error.auth.refresh.revoked");
                });
    }

    // Feature: FOR-03-01-jwt-auth, Property 19: Expired refresh token is rejected
    // For all persisted refresh tokens whose expiry is in the past, rotate raises a 401 with
    // message code error.auth.refresh.expired.
    // Validates: Requirements 7.6
    @Property(tries = 100)
    void expiredTokenRejected(@ForAll("userIds") long userId,
                              @ForAll @LongRange(min = 1, max = 1_000_000) long secondsAgo) {
        Fixture fx = fixture(7);
        UserEntity user = user(userId);
        String token = fx.service().issue(user);

        // Force the persisted token into the past.
        RefreshTokenEntity persisted = fx.store().findByToken(token).orElseThrow();
        persisted.setExpiresAt(Instant.now().minusSeconds(secondsAgo));

        assertThatThrownBy(() -> fx.service().rotate(token))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getMessageCode()).isEqualTo("error.auth.refresh.expired");
                });
    }

    // Feature: FOR-03-01-jwt-auth, Property 20: Logout revokes a known refresh token
    // For all persisted refresh tokens, a logout (revoke) supplying that token sets its revoked
    // value to true. (HTTP 204 is produced by AuthController; the service-level effect is the
    // revocation, which subsequently makes the token unusable for rotation.)
    // Validates: Requirements 8.2
    @Property(tries = 100)
    void logoutRevokesKnownToken(@ForAll("userIds") long userId) {
        Fixture fx = fixture(7);
        UserEntity user = user(userId);
        String token = fx.service().issue(user);

        fx.service().revoke(token);

        RefreshTokenEntity persisted = fx.store().findByToken(token).orElseThrow();
        assertThat(persisted.isRevoked()).as("logout must revoke the known token").isTrue();

        // And the revoked token can no longer be rotated.
        assertThatThrownBy(() -> fx.service().rotate(token))
                .isInstanceOf(ForemenApiException.class);
    }

    // Feature: FOR-03-01-jwt-auth, Property 21: Logout is idempotent for unknown tokens
    // For all refresh-token values matching no persisted entity, a logout (revoke) raises no
    // error. (The controller returns HTTP 204 without error.)
    // Validates: Requirements 8.3
    @Property(tries = 100)
    void logoutIdempotentForUnknownTokens(@ForAll("tokenValues") String unknownToken) {
        Fixture fx = fixture(7);

        assertThatCode(() -> fx.service().revoke(unknownToken))
                .as("revoking an unknown token must not raise an error")
                .doesNotThrowAnyException();
    }

    // --- Providers ---

    /** User ids: positive longs across a broad range. */
    @Provide
    Arbitrary<Long> userIds() {
        return Arbitraries.longs().between(1L, Long.MAX_VALUE);
    }

    /** Arbitrary non-blank opaque token values (never issued by the service under test). */
    @Provide
    Arbitrary<String> tokenValues() {
        Arbitrary<String> body = Arbitraries.strings()
                .withCharRange('!', '~')
                .ofMinLength(1)
                .ofMaxLength(48);
        // Prefix keeps them distinct from any base64url value the service could produce and
        // guarantees non-blankness.
        return Combinators.combine(Arbitraries.of("unknown-"), body).as((p, b) -> p + b);
    }
}
