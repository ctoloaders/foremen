package com.foremen.service.translation;

import com.foremen.config.persistence.LocaleAttributeConverter;
import com.foremen.config.translation.TranslationNotConfiguredCondition;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * FOR-06-12 (Requirement 10.3, 6.3, design §"MessageTranslationService seam") — the fallback
 * {@link MessageTranslationService} wired when no translation provider is configured. Selected via
 * {@link TranslationNotConfiguredCondition} (the negation of
 * {@link com.foremen.config.translation.TranslationConfiguredCondition}), so exactly one of this
 * disabled fallback and the Gemini-backed {@link GeminiMessageTranslationService} is present in the
 * context.
 *
 * <p>Behaviour with no provider:
 * <ul>
 *   <li>{@link #translate} always returns {@link Optional#empty()} — the message stays available in
 *       its original locale only (R6.3);</li>
 *   <li>{@link #detectLocale} falls back to the author-preferred locale, resolving to PL
 *       ({@link LocaleAttributeConverter#FALLBACK}) when it is {@code null} or unsupported, reusing
 *       {@link LocaleAttributeConverter#isSupported(Locale)} (R10.3);</li>
 *   <li>{@link #isConfigured()} is {@code false}.</li>
 * </ul>
 */
@Service
@Conditional(TranslationNotConfiguredCondition.class)
public class DisabledMessageTranslationService implements MessageTranslationService {

    /**
     * {@inheritDoc}
     *
     * <p>No provider configured: always {@link Optional#empty()} so the caller leaves the target
     * locale untranslated (R6.3).
     */
    @Override
    public Optional<String> translate(String text, Locale source, Locale target) {
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     *
     * <p>No provider configured: fall back to {@code authorPreferred} when it is a supported locale,
     * otherwise PL ({@link LocaleAttributeConverter#FALLBACK}) (R10.3).
     */
    @Override
    public Locale detectLocale(String text, Locale authorPreferred) {
        return LocaleAttributeConverter.isSupported(authorPreferred)
                ? authorPreferred
                : LocaleAttributeConverter.FALLBACK;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code false} — this is the disabled fallback.
     */
    @Override
    public boolean isConfigured() {
        return false;
    }
}
