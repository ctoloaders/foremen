package com.foremen.controller;

import com.foremen.controller.dto.auth.CurrentUserResponse;
import com.foremen.controller.dto.bot.BotIdentityTokenResponse;
import com.foremen.service.AuthService;
import com.foremen.service.BotIdentityTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the loaders-bot identity-token endpoint under {@code /api/bot}
 * (FOR-08, Requirement 1).
 *
 * <p>{@code POST /api/bot/identity-token} is an authenticated self-service operation: it returns a
 * fresh HS256 identity token for the caller so the bot knows who the user is (Requirement 1.1). It
 * requires a valid Foremen access token — an unauthenticated request is rejected with HTTP 401 by
 * the security layer (Requirement 1.4).
 *
 * <p>Like the {@code /api/auth} self-service surface, this endpoint carries <b>none</b> of the
 * three permission annotations ({@code @PermissionResource}/{@code @PermissionOperation}/
 * {@code @RequiresPermission}) — it is deliberately Unguarded, reachable by any authenticated role
 * regardless of Company_Role, and therefore passes {@code PermissionAnnotationValidator}
 * (Requirement 1.5). It introduces no entity, table, or changeset (Requirement 1.7).
 */
@RestController
@RequestMapping("/api/bot")
@RequiredArgsConstructor
public class BotIdentityController {

    private final AuthService authService;
    private final BotIdentityTokenService tokenService;

    /**
     * Issues a signed loaders-bot identity token for the authenticated caller (Requirement 1.1,
     * 1.2).
     *
     * <p>The principal is the numeric user id ({@code sub} claim) established by the JWT filter.
     * The user's display name and Company_Role code are resolved from the live role graph via
     * {@link AuthService#currentUser(Long)} (the same source {@code GET /api/auth/me} uses). The
     * locale trait is derived from the request's {@code Accept-Language}: {@code ru} when it
     * resolves to Russian, otherwise {@code pl}.
     *
     * @param userId         the authenticated user id (JWT {@code sub})
     * @param acceptLanguage the inbound {@code Accept-Language} header, if any
     * @return HTTP 200 with a {@link BotIdentityTokenResponse} carrying the signed token
     */
    @PostMapping("/identity-token")
    public ResponseEntity<BotIdentityTokenResponse> identityToken(
            @AuthenticationPrincipal Long userId,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        CurrentUserResponse me = authService.currentUser(userId);
        String locale = resolveLocale(acceptLanguage);
        String token = tokenService.sign(me.id(), me.name(), locale, me.roleCode());
        return ResponseEntity.ok(new BotIdentityTokenResponse(token));
    }

    /**
     * Resolves the identity-token {@code locale} trait from the request's {@code Accept-Language}:
     * {@code ru} when the preferred language is Russian, otherwise {@code pl} (Requirement 1.2).
     * The project supports exactly two locales (ru/pl), so anything that is not Russian falls back
     * to Polish.
     */
    private String resolveLocale(String acceptLanguage) {
        if (acceptLanguage == null) {
            return "pl";
        }
        String normalized = acceptLanguage.trim().toLowerCase();
        return normalized.startsWith("ru") ? "ru" : "pl";
    }
}
