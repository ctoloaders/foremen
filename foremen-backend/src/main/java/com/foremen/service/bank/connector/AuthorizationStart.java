package com.foremen.service.bank.connector;

import java.net.URI;
import java.time.Instant;

/**
 * FOR-07-04 (Requirements 1.3, 9.1, 9.4): result of opening an authorization / reconnect session.
 *
 * @param authorizationUrl   URL the frontend redirects the user to (Salt Edge {@code connect_url} /
 *                           {@code redirect_url}, or the stub return URL)
 * @param expiresAt          session expiry; nullable
 * @param externalCustomerId provider customer / lead id ensured for the session; nullable
 */
public record AuthorizationStart(URI authorizationUrl, Instant expiresAt, String externalCustomerId) {

    public AuthorizationStart {
        if (authorizationUrl == null) {
            throw new IllegalArgumentException("authorizationUrl is required");
        }
    }
}
