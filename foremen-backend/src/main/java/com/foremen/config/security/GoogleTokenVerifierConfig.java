package com.foremen.config.security;

import java.util.Collections;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

/**
 * Wires the {@link GoogleIdTokenVerifier} used by {@code AuthService.loginWithGoogle}
 * to verify inbound Google ID tokens on {@code POST /api/auth/google}.
 *
 * <p>The verifier is configured with the audience taken from {@link GoogleProperties#clientId()}
 * ({@code foremen.google.client-id}), so a token whose {@code aud} claim does not match the
 * configured Google OAuth client is rejected. It is exposed as a Spring bean (rather than being
 * constructed inline in the service) so that it is injectable and can be stubbed in tests.
 *
 * <p>When {@code foremen.google.client-id} is unset (Google Sign-In not configured in this
 * environment) the audience list is empty, so every token fails audience validation and the
 * exchange endpoint consistently rejects Google logins — context startup is unaffected.
 */
@Configuration
public class GoogleTokenVerifierConfig {

    @Bean
    public GoogleIdTokenVerifier googleIdTokenVerifier(GoogleProperties googleProperties) {
        String clientId = googleProperties.clientId();
        GoogleIdTokenVerifier.Builder builder =
                new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance());
        if (clientId != null && !clientId.isBlank()) {
            builder.setAudience(Collections.singletonList(clientId));
        } else {
            builder.setAudience(Collections.emptyList());
        }
        return builder.build();
    }
}
