package com.foremen.service.clientmessage;

import java.util.Locale;

/**
 * FOR-06-12 (Requirements 2.1, 2.2, 6.1) — the write model for posting a message
 * ({@code POST /api/client-messages}, {@code multipart/form-data}: this JSON part + the optional
 * media files handled separately at the controller).
 *
 * <p>Carries the message {@link #text} and an optional explicit {@link #locale}. When
 * {@code locale} is {@code null} the service detects the original locale via
 * {@code MessageTranslationService.detectLocale(text, authorPreferred)} using the author's preferred
 * locale as the fallback (R6.1); when supplied (and supported) it is taken as the author's declared
 * original locale.
 *
 * <p>The request carries <strong>no</strong> {@code authorSide} — the side is derived from the
 * author's project role at post time, never from the body (R2.1). A post with blank {@code text} and
 * no media is rejected by the service with {@code 400 error.clientMessage.empty} (R2.2).
 *
 * @param text   the message text as the author wrote it (required unless media is attached)
 * @param locale the author's explicit original locale, or {@code null} to auto-detect
 */
public record PostMessageRequest(
        String text,
        Locale locale) {
}
