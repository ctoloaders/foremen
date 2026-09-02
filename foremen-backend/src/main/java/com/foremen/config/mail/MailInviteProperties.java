package com.foremen.config.mail;

import java.net.URI;
import java.net.URISyntaxException;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

/**
 * Configuration properties for invitation-email link construction, bound from the
 * {@code foremen.mail} namespace in {@code application.yml} (Requirement 7.1, 7.2).
 *
 * <p>Validated at startup for fail-fast behavior (mirroring {@code JwtProperties}):
 * {@code @NotBlank} rejects an empty or blank base URL, and the {@link #isInviteBaseUrlValid()}
 * predicate rejects any value that is not a syntactically valid absolute {@code http}/{@code https}
 * URL, aborting context startup with a clear configuration error.
 *
 * <p>Only the {@code invite-base-url} key is bound here; the existing {@code foremen.mail.from} and
 * {@code foremen.mail.reset-base-url} keys continue to be read via {@code @Value} in
 * {@code SmtpMailSender} and are left unbound by this record.
 */
@Validated
@ConfigurationProperties(prefix = "foremen.mail")
public record MailInviteProperties(@NotBlank String inviteBaseUrl) {

    /**
     * Accepts only a syntactically valid absolute {@code http} or {@code https} URL (Requirement 7.2).
     */
    @AssertTrue(message = "foremen.mail.invite-base-url must be an absolute http/https URL")
    public boolean isInviteBaseUrlValid() {
        if (inviteBaseUrl == null || inviteBaseUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(inviteBaseUrl);
            String scheme = uri.getScheme();
            return uri.isAbsolute()
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
