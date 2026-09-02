package com.foremen.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Pure helper that builds a set-password invitation link from a configured base URL and an
 * invite token (see FOR-03-02, Requirements 7.3 and 7.4).
 *
 * <p>The token value is URL-encoded (UTF-8) before being appended as the {@code token} query
 * parameter. The separator is chosen based on whether the base URL already contains a query
 * string: {@code ?token=...} when the base URL has no {@code ?}, otherwise {@code &token=...}.
 *
 * <p>This class holds no state and is safe to reuse from {@code InviteService} (task 7).
 */
public final class InviteLinkBuilder {

    private InviteLinkBuilder() {
        // utility class; not instantiable
    }

    /**
     * Constructs the invite link {@code {baseUrl}?token={encoded}} (or {@code &token=} when the
     * base URL already contains a {@code ?}).
     *
     * @param baseUrl the configured invitation base URL
     * @param token   the raw invite-token value
     * @return the fully-qualified invite link with the URL-encoded token appended
     */
    public static String buildInviteLink(String baseUrl, String token) {
        String encoded = URLEncoder.encode(token, StandardCharsets.UTF_8); // 7.4
        String separator = baseUrl.indexOf('?') >= 0 ? "&" : "?";          // 7.3
        return baseUrl + separator + "token=" + encoded;
    }
}
