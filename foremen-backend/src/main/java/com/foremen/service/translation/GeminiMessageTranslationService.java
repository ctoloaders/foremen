package com.foremen.service.translation;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Optional;

import org.springframework.context.annotation.Conditional;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.foremen.config.persistence.LocaleAttributeConverter;
import com.foremen.config.translation.TranslationConfiguredCondition;
import com.foremen.config.translation.TranslationProperties;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-06-12 (Requirement 10.2, 10.4, design §"MessageTranslationService seam") — the Google Gen AI
 * (Gemini) backed {@link MessageTranslationService}: a thin I/O orchestrator that prompts the Gemini
 * model to (a) translate a message between the application's supported locales (PL/RU) and (b)
 * classify a message's original locale. Active only when {@code foremen.translation.enabled=true}
 * ({@link TranslationConfiguredCondition}); otherwise the
 * {@link DisabledMessageTranslationService} fallback is wired
 * ({@link com.foremen.config.translation.TranslationNotConfiguredCondition}).
 *
 * <p>All provider credentials + the LLM model are read from the {@code foremen.translation.*} block
 * via {@link TranslationProperties} — never hardcoded (R10.2). The Gemini client is built lazily on
 * first use so a credential problem surfaces as a handled per-call failure (translation →
 * {@link Optional#empty()}; detection → the preferred-locale fallback) rather than a startup hang —
 * the same lazy-client approach as the FOR-06-03 OCR wiring, re-implemented here without importing
 * or reusing any OCR code (R10.2).
 *
 * <p><strong>Never throws for provider errors</strong> (R6.3, R10.4): {@link #translate} logs and
 * returns {@link Optional#empty()} on any failure; {@link #detectLocale} logs and falls back to the
 * author-preferred locale (PL when {@code null}/unsupported/inconclusive).
 */
@Slf4j
@Service
@Conditional(TranslationConfiguredCondition.class)
public class GeminiMessageTranslationService implements MessageTranslationService {

    private final TranslationProperties properties;

    /** Lazily-built Gemini client (not built in a {@code @PostConstruct} so a bad credential is a
     * handled per-call failure, not a startup failure). */
    private volatile Client genAiClient;
    private final Object genAiLock = new Object();

    public GeminiMessageTranslationService(TranslationProperties properties) {
        this.properties = properties;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sends a strict translate prompt (output the translation only, no commentary). On any
     * provider/call failure, or a blank model response, logs and returns {@link Optional#empty()} so
     * the caller leaves that target locale untranslated (R6.3, R10.4).
     */
    @Override
    public Optional<String> translate(String text, Locale source, Locale target) {
        if (text == null || text.isBlank() || source == null || target == null) {
            return Optional.empty();
        }
        try {
            String prompt = buildTranslatePrompt(text, source, target);
            String response = generateText(prompt);
            if (response == null || response.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(response.strip());
        } catch (RuntimeException e) {
            log.warn("Translation provider call failed ({} -> {}); leaving the target untranslated: {}",
                    languageTag(source), languageTag(target), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sends a locale-classification prompt constrained to the supported set (PL/RU) that must
     * answer with a single language tag. On any provider/call failure, or an inconclusive /
     * unsupported answer, logs and falls back to {@code authorPreferred} — resolving to PL
     * ({@link LocaleAttributeConverter#FALLBACK}) when it is {@code null} or unsupported (R10.3,
     * R10.4).
     */
    @Override
    public Locale detectLocale(String text, Locale authorPreferred) {
        Locale fallback = LocaleAttributeConverter.isSupported(authorPreferred)
                ? authorPreferred
                : LocaleAttributeConverter.FALLBACK;
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            String response = generateText(buildDetectPrompt(text));
            Locale detected = parseDetectedLocale(response);
            return detected != null ? detected : fallback;
        } catch (RuntimeException e) {
            log.warn("Locale-detection provider call failed; falling back to the preferred locale {}: {}",
                    languageTag(fallback), e.getMessage());
            return fallback;
        }
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} — this is the configured, provider-backed implementation.
     */
    @Override
    public boolean isConfigured() {
        return true;
    }

    // --- Prompts ---------------------------------------------------------------------------------

    /** A strict translate prompt: output only the translation, no commentary or quotes. */
    private String buildTranslatePrompt(String text, Locale source, Locale target) {
        return "You are a professional translator for a construction-management application. "
                + "Translate the message below from " + languageName(source) + " (" + languageTag(source)
                + ") into " + languageName(target) + " (" + languageTag(target) + "). "
                + "Preserve meaning, tone, numbers, and any formatting. "
                + "Output ONLY the translated text, with no quotes, labels, or commentary.\n\n"
                + "MESSAGE:\n" + text;
    }

    /**
     * A locale-classification prompt constrained to the supported set (PL/RU). The model must answer
     * with exactly one lowercase language tag — {@code pl} or {@code ru} — and nothing else.
     */
    private String buildDetectPrompt(String text) {
        return "Classify the language of the message below. It is one of exactly two languages: "
                + "Polish or Russian. Answer with EXACTLY one lowercase language tag and nothing "
                + "else: \"pl\" for Polish or \"ru\" for Russian.\n\n"
                + "MESSAGE:\n" + text;
    }

    /**
     * Parse a locale-classification response into a supported {@link Locale}, or {@code null} when
     * the answer is blank, inconclusive, or not one of the supported languages. Tolerant of
     * surrounding whitespace, quotes, and trailing punctuation around a {@code pl}/{@code ru} tag.
     */
    private Locale parseDetectedLocale(String response) {
        if (response == null) {
            return null;
        }
        String normalized = response.strip().toLowerCase(Locale.ROOT);
        if (normalized.contains("ru")) {
            return LocaleAttributeConverter.RU;
        }
        if (normalized.contains("pl")) {
            return LocaleAttributeConverter.PL;
        }
        return null;
    }

    private String languageTag(Locale locale) {
        return locale == null ? "" : locale.toLanguageTag().toLowerCase(Locale.ROOT);
    }

    private String languageName(Locale locale) {
        if (locale == null) {
            return "the source language";
        }
        return locale.getDisplayLanguage(Locale.ENGLISH);
    }

    // --- LLM call ---------------------------------------------------------------------------------

    /** Send a text-only prompt to the Gemini model and return the model's text response. */
    private String generateText(String prompt) {
        GenerateContentResponse response =
                genAi().models.generateContent(resolveModel(), prompt, null);
        String text = response.text();
        return text == null ? "" : text;
    }

    private String resolveModel() {
        String model = properties.model();
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("foremen.translation.model is not configured");
        }
        return model.strip();
    }

    // --- Lazy client construction (credentials from config, never hardcoded — R10.2) -------------

    private Client genAi() {
        Client local = this.genAiClient;
        if (local == null) {
            synchronized (genAiLock) {
                local = this.genAiClient;
                if (local == null) {
                    local = buildGenAiClient();
                    this.genAiClient = local;
                }
            }
        }
        return local;
    }

    private Client buildGenAiClient() {
        Client.Builder builder = Client.builder();
        String apiKey = properties.apiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            // Gemini Developer API backend, keyed.
            builder.apiKey(apiKey.strip());
        } else {
            // Gemini Enterprise Agent Platform (Vertex) backend via ADC + project/location.
            builder.vertexAI(true);
            if (properties.project() != null && !properties.project().isBlank()) {
                builder.project(properties.project().strip());
            }
            if (properties.location() != null && !properties.location().isBlank()) {
                builder.location(properties.location().strip());
            }
            GoogleCredentials credentials = resolveCredentials(properties.credentials());
            if (credentials != null) {
                builder.credentials(credentials);
            }
        }
        return builder.build();
    }

    /**
     * Resolve {@link GoogleCredentials} from an explicit {@code path}/{@code location}, falling back
     * to Application Default Credentials when neither is set or the {@code application-default}
     * sentinel is used. Same resolution approach as the OCR/image-storage wiring, re-implemented
     * here (no OCR code import — R10.2).
     */
    private GoogleCredentials resolveCredentials(TranslationProperties.Credentials creds) {
        if (creds == null) {
            return null;
        }
        String location = creds.path() != null && !creds.path().isBlank()
                ? creds.path()
                : creds.location();
        if (location == null || location.isBlank()
                || "application-default".equalsIgnoreCase(location.strip())) {
            return null; // fall back to Application Default Credentials
        }
        Resource resource = new DefaultResourceLoader().getResource(location.strip());
        try (InputStream in = resource.getInputStream()) {
            return GoogleCredentials.fromStream(in);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "failed to load Google credentials from '" + location + "': " + e.getMessage(), e);
        }
    }
}
