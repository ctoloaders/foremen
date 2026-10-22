package com.foremen.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.foremen.config.bot.BotProperties;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;

/**
 * Signs the loaders-bot identity token (FOR-08, Requirement 1).
 *
 * <p>The token is an HS256 JWT whose {@code sub} is the Foremen user id, with {@code iat} = now and
 * {@code exp} ≤ 24 h from now (here 12 h), a {@code traits} claim carrying the user's display
 * {@code name} and current {@code locale} (ru/pl), and a {@code roles} claim carrying the user's
 * Company_Role code — the key the bot uses to map the visitor to an agent role (Requirement 1.1,
 * 1.2; design D5).
 *
 * <p>The signing secret is read from {@link BotProperties#identitySecret()} (env
 * {@code FOREMEN_BOT_IDENTITY_SECRET}) and is backend-only — it never appears in a response or a
 * log (Requirement 1.3). The secret is validated at startup: a blank secret fails fast via
 * {@link #validateSecret()} so the application never signs tokens with an empty key (Requirement
 * 1.6), mirroring the HS256 minimum-length check in {@code JwtTokenProvider}.
 *
 * <p>The service is stateless: it persists nothing and introduces no entity, table, or changeset
 * (Requirement 1.7).
 */
@Service
public class BotIdentityTokenService {

    /** Minimum key length in bytes required by HS256. */
    private static final int MIN_KEY_LENGTH_BYTES = 32;

    /** Identity token lifetime; kept well within the ≤ 24 h cap (Requirement 1.1). */
    private static final Duration TOKEN_TTL = Duration.ofHours(12);

    private final SecretKey signingKey;

    public BotIdentityTokenService(BotProperties botProperties) {
        String secret = botProperties.identitySecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "foremen.bot.identity-secret (env FOREMEN_BOT_IDENTITY_SECRET) is blank; "
                            + "set the loaders-bot identity secret so the identity token is never "
                            + "signed with an empty key.");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "foremen.bot.identity-secret must be at least " + MIN_KEY_LENGTH_BYTES
                            + " bytes for HS256 signing (was " + keyBytes.length + " bytes).");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Fail-fast marker executed after construction. The actual validation happens in the
     * constructor (so the bean cannot even be created with a blank secret); this hook exists so the
     * invariant is explicit and the startup failure is surfaced as a clear configuration error
     * (Requirement 1.6).
     */
    @PostConstruct
    void validateSecret() {
        // Constructor already enforced a non-blank, long-enough secret; nothing further to do.
    }

    /**
     * Signs the identity token for the given user (Requirement 1.1, 1.2).
     *
     * @param userId   the Foremen user id — becomes the {@code sub} claim
     * @param name     the user's display name — carried in {@code traits.name}
     * @param locale   the user's current locale ({@code ru}/{@code pl}) — carried in
     *                 {@code traits.locale}
     * @param roleCode the user's Company_Role code — carried as the single-element {@code roles}
     *                 claim used for bot role mapping
     * @return the compact HS256-signed JWT
     */
    public String sign(Long userId, String name, String locale, String roleCode) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TOKEN_TTL)))
                .claim("traits", Map.of("name", name, "locale", locale))
                .claim("roles", List.of(roleCode))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }
}
