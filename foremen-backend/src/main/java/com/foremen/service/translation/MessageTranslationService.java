package com.foremen.service.translation;

import java.util.Locale;
import java.util.Optional;

/**
 * FOR-06-12 (Requirement 10, design §"MessageTranslationService seam") — the pluggable client↔staff
 * message translation seam. Given a message's text it can (a) classify the original locale and
 * (b) best-effort translate the text between the application's supported locales (PL/RU), so the
 * {@code ClientMessageService} can persist an original-locale mirror plus machine translations for
 * the other supported locale(s).
 *
 * <p>Two wirings exist so the application runs with or without a translation provider configured
 * (R10.1, R10.3):
 * <ul>
 *   <li>{@link GeminiMessageTranslationService}
 *       ({@code @Conditional(TranslationConfiguredCondition)}, task 2.4) — the Google Gen AI
 *       (Gemini) backed implementation; {@link #isConfigured()} is {@code true}.</li>
 *   <li>{@link DisabledMessageTranslationService}
 *       ({@code @Conditional(TranslationNotConfiguredCondition)}, the default fallback) —
 *       {@link #isConfigured()} is {@code false}, {@link #translate} returns
 *       {@link Optional#empty()}, and {@link #detectLocale} falls back to the author-preferred
 *       locale (PL when {@code null}/unsupported).</li>
 * </ul>
 *
 * <p><strong>Never throws for provider errors.</strong> Translation/detection provider failures are
 * not surfaced as errors: {@link #translate} returns {@link Optional#empty()} ("no translation") and
 * {@link #detectLocale} always returns a supported {@link Locale} (R6.3, R10.4). Exactly one
 * implementation is present in the context at a time, selected by the mutually exclusive conditions.
 */
public interface MessageTranslationService {

    /**
     * Best-effort translation of {@code text} from {@code source} to {@code target}. Returns
     * {@link Optional#empty()} when translation is unavailable (no provider configured) or the
     * provider call fails — never throws for provider errors (R6.3, R10.4). A caller that receives
     * an empty result simply leaves that target locale untranslated.
     *
     * @param text   the source text to translate; never {@code null}
     * @param source the source locale (the detected original locale); never {@code null}
     * @param target the target locale to translate into; never {@code null}
     * @return the translated text, or {@link Optional#empty()} when unavailable/failed
     */
    Optional<String> translate(String text, Locale source, Locale target);

    /**
     * Classify the original locale of {@code text}, using {@code authorPreferred} as the fallback.
     * Always returns one of the application's supported locales (PL/RU): when no provider is
     * configured, or classification is inconclusive, this falls back to {@code authorPreferred}
     * (PL when {@code null} or unsupported).
     *
     * @param text            the message text whose locale should be detected; never {@code null}
     * @param authorPreferred the author's preferred locale, used as the fallback; may be
     *                        {@code null}
     * @return a supported {@link Locale} (PL/RU); never {@code null}
     */
    Locale detectLocale(String text, Locale authorPreferred);

    /**
     * Whether a translation provider is configured and active. When {@code false} the disabled
     * fallback is wired: {@link #translate} returns empty and {@link #detectLocale} falls back to
     * the preferred locale (R10.3).
     *
     * @return {@code true} when a translation provider is configured
     */
    boolean isConfigured();
}
