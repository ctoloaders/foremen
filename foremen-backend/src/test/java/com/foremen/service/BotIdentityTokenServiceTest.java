package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.foremen.config.bot.BotProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Unit tests for {@link BotIdentityTokenService} (FOR-08, Requirement 1 / verification 8.1).
 *
 * <p>These are fast, context-free tests exercising the signing service directly:
 * <ul>
 *   <li>a signed token is a valid HS256 JWT whose {@code sub} is the user id, whose
 *       {@code traits} claim carries {@code name}/{@code locale}, and whose {@code roles} claim
 *       carries the single Company_Role code, with {@code iat} = now and {@code exp} ≤ 24 h
 *       (Requirement 1.1, 1.2);</li>
 *   <li>the service fails fast at construction when the identity secret is blank, empty, or
 *       {@code null}, so a token is never signed with an empty key (Requirement 1.6);</li>
 *   <li>a too-short secret (&lt; 32 bytes) is also rejected, matching the HS256 minimum-key-length
 *       guard.</li>
 * </ul>
 *
 * <p>The token is verified by parsing it with the same secret (the 401/endpoint-wiring concerns are
 * covered by {@code BotIdentityControllerIntegrationTest}).
 */
class BotIdentityTokenServiceTest {

    /** A 32+-byte secret, long enough for HS256. */
    private static final String VALID_SECRET = "bot-identity-secret-unit-test-0123456789-abcdef";

    private static final long USER_ID = 42L;
    private static final String NAME = "Jane Doe";
    private static final String ROLE_CODE = "ESTIMATOR";

    private static BotIdentityTokenService serviceWith(String secret) {
        return new BotIdentityTokenService(new BotProperties(secret, "pk_test_foremen"));
    }

    private static Jws<Claims> parse(String secret, String token) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
    }

    // ---------------------------------------------------------------------
    // Valid token (Requirement 1.1, 1.2)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("sign() produces an HS256 token with the correct sub/traits.locale/roles (1.1, 1.2)")
    void signProducesTokenWithCorrectClaims() {
        BotIdentityTokenService service = serviceWith(VALID_SECRET);

        String token = service.sign(USER_ID, NAME, "ru", ROLE_CODE);

        Claims claims = parse(VALID_SECRET, token).getPayload();

        // sub = user id (Requirement 1.1)
        assertThat(claims.getSubject()).isEqualTo(String.valueOf(USER_ID));

        // traits.name + traits.locale (Requirement 1.2)
        @SuppressWarnings("unchecked")
        Map<String, Object> traits = claims.get("traits", Map.class);
        assertThat(traits).containsEntry("name", NAME).containsEntry("locale", "ru");

        // roles = [roleCode] (Requirement 1.2)
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);
        assertThat(roles).containsExactly(ROLE_CODE);
    }

    @Test
    @DisplayName("sign() sets iat=now and exp within (now, now + 24h] (1.1)")
    void signSetsIssuedAtNowAndExpiryWithin24h() {
        BotIdentityTokenService service = serviceWith(VALID_SECRET);
        Instant before = Instant.now().minusSeconds(1);

        String token = service.sign(USER_ID, NAME, "pl", ROLE_CODE);
        Instant after = Instant.now().plusSeconds(1);

        Claims claims = parse(VALID_SECRET, token).getPayload();
        Date iat = claims.getIssuedAt();
        Date exp = claims.getExpiration();

        assertThat(iat).isNotNull();
        assertThat(exp).isNotNull();

        assertThat(iat.toInstant()).isBetween(before, after);

        // exp must be after iat and no more than 24 h beyond it (Requirement 1.1 cap).
        assertThat(exp.toInstant()).isAfter(iat.toInstant());
        assertThat(exp.toInstant())
                .isBeforeOrEqualTo(iat.toInstant().plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("sign() carries the locale trait verbatim (pl path) (1.2)")
    void signCarriesPolishLocale() {
        BotIdentityTokenService service = serviceWith(VALID_SECRET);

        String token = service.sign(USER_ID, NAME, "pl", "MANAGER");

        @SuppressWarnings("unchecked")
        Map<String, Object> traits = parse(VALID_SECRET, token).getPayload().get("traits", Map.class);
        assertThat(traits).containsEntry("locale", "pl");
    }

    // ---------------------------------------------------------------------
    // Fail-fast on blank/missing secret (Requirement 1.6)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("construction fails fast when the identity secret is null (1.6)")
    void constructionFailsFastOnNullSecret() {
        assertThatThrownBy(() -> serviceWith(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity-secret");
    }

    @Test
    @DisplayName("construction fails fast when the identity secret is empty (1.6)")
    void constructionFailsFastOnEmptySecret() {
        assertThatThrownBy(() -> serviceWith(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity-secret");
    }

    @Test
    @DisplayName("construction fails fast when the identity secret is blank whitespace (1.6)")
    void constructionFailsFastOnBlankSecret() {
        assertThatThrownBy(() -> serviceWith("   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity-secret");
    }

    @Test
    @DisplayName("construction fails fast when the identity secret is shorter than 32 bytes")
    void constructionFailsFastOnTooShortSecret() {
        assertThatThrownBy(() -> serviceWith("too-short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }
}
