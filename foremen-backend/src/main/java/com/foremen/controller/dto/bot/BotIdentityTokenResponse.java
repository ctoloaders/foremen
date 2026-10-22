package com.foremen.controller.dto.bot;

/**
 * Response body of {@code POST /api/bot/identity-token} (FOR-08, Requirement 1.1).
 *
 * <p>Carries only the signed HS256 loaders-bot identity token; the signing secret itself is never
 * part of any response (Requirement 1.3). The browser passes this token to
 * {@code LoadersBot.identify({ token })}.
 */
public record BotIdentityTokenResponse(String token) {
}
