package com.foremen.config.bot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the loaders-bot integration, bound from the {@code foremen.bot}
 * namespace in {@code application.yml} (and the per-profile overrides).
 *
 * <p>Holds the server-side HS256 {@link #identitySecret()} used to sign the loaders-bot identity
 * token (FOR-08, Requirement 1.3, 1.6) and the public {@link #siteKey()} surfaced to the frontend
 * (Requirement 6.1, 7.1). Both bind from environment variables and are empty by default so the
 * application boots in environments where the assistant is not configured — the fail-fast on a
 * blank identity secret lives in {@code BotIdentityTokenService} (it only matters once a token is
 * actually signed), mirroring how the JWT secret is validated in its provider rather than here.
 *
 * <p>The identity secret is backend-only and MUST never reach frontend code, logs, or any response
 * (Requirement 1.3); the site key is public by design (loaders-bot {@code pk_test_}/{@code pk_live_}).
 */
@Validated
@ConfigurationProperties(prefix = "foremen.bot")
public record BotProperties(String identitySecret, String siteKey) {
}
