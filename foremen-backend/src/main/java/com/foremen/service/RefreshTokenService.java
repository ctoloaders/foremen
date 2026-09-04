package com.foremen.service;

import com.foremen.config.security.JwtProperties;
import com.foremen.dao.RefreshTokenDao;
import com.foremen.dao.model.RefreshTokenEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Manages the lifecycle of {@link RefreshTokenEntity} rows: issuance, rotation, revocation.
 *
 * <p>Refresh tokens are opaque, 256-bit random values (base64url-encoded) persisted in the
 * {@code refresh_tokens} table. They are subject to one-time-use rotation and revocation.
 *
 * <p>The rotation path distinguishes the not-found, revoked, and expired cases so the caller
 * can surface the correct 401 message code:
 * <ul>
 *   <li>not found &rarr; {@code error.auth.refresh.invalid}</li>
 *   <li>revoked &rarr; {@code error.auth.refresh.revoked}</li>
 *   <li>expired &rarr; {@code error.auth.refresh.expired}</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RefreshTokenService {

    /** 256 bits of randomness for the opaque token value. */
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenDao dao;
    private final JwtProperties jwtProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Issues a new refresh token for the given user with the employee refresh TTL
     * ({@code foremen.jwt.refresh-ttl-days}). Delegates to {@link #issue(UserEntity, int)}.
     *
     * <p>Used by the FOR-03-01 employee login and refresh flows; its lifetime is unaffected by
     * the client TTLs (Requirement 7.4).
     *
     * @return the opaque token value to hand back to the client
     */
    public String issue(UserEntity user) {
        return issue(user, jwtProperties.refreshTtlDays());
    }

    /**
     * Issues a new refresh token for the given user, persisting a fresh {@link RefreshTokenEntity}
     * with a 256-bit random value, {@code revoked=false}, and an expiry {@code ttlDays} days after
     * issuance.
     *
     * <p>TTL-aware overload used by the OTP verify path so client refresh tokens can be issued with
     * the client refresh TTL ({@code foremen.jwt.client-refresh-ttl-days}) independently of the
     * employee refresh TTL (Requirement 7.3).
     *
     * @param ttlDays the refresh-token lifetime in days
     * @return the opaque token value to hand back to the client
     */
    public String issue(UserEntity user, int ttlDays) {
        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.setToken(generateTokenValue());
        entity.setUser(user);
        entity.setExpiresAt(Instant.now().plus(Duration.ofDays(ttlDays)));
        entity.setRevoked(false);
        return dao.save(entity).getToken();
    }

    /**
     * Validates the supplied token and, on success, revokes it (one-time-use rotation) and returns
     * the persisted entity so the caller can identify the owning user and issue a fresh pair.
     *
     * @throws ForemenApiException 401 {@code error.auth.refresh.invalid} if the token matches no row,
     *                             401 {@code error.auth.refresh.revoked} if the token is already revoked,
     *                             401 {@code error.auth.refresh.expired} if the token has expired
     */
    public RefreshTokenEntity rotate(String token) {
        RefreshTokenEntity entity = dao.findByToken(token)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.refresh.invalid"));

        if (entity.isRevoked()) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.refresh.revoked");
        }
        if (!entity.getExpiresAt().isAfter(Instant.now())) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.refresh.expired");
        }

        entity.setRevoked(true);
        dao.save(entity);
        return entity;
    }

    /**
     * Revokes the token if it exists. Idempotent: an unknown token is a no-op and raises no error.
     */
    public void revoke(String token) {
        dao.findByToken(token).ifPresent(entity -> {
            if (!entity.isRevoked()) {
                entity.setRevoked(true);
                dao.save(entity);
            }
        });
    }

    /**
     * Revokes every non-revoked refresh token owned by the given user. Used by the password-reset
     * flow so all of the user's sessions are invalidated once the password changes.
     */
    public void revokeAllForUser(Long userId) {
        List<RefreshTokenEntity> active = dao.findByUserIdAndRevokedFalse(userId);
        for (RefreshTokenEntity entity : active) {
            entity.setRevoked(true);
        }
        dao.saveAll(active);
    }

    private String generateTokenValue() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
