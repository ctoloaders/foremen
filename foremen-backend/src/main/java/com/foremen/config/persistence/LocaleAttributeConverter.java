package com.foremen.config.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Locale;
import java.util.Set;

/**
 * Shared JPA {@link AttributeConverter} between {@link Locale} and its stored lowercase language
 * tag (e.g. {@code "pl"} / {@code "ru"}).
 *
 * <p>This is the single locale representation used across the codebase (message locales and the
 * migrated {@code UserEntity.locale}). It is backwards compatible with the existing
 * {@code users.locale} {@code VARCHAR} column, which already holds the lowercase {@code "pl"} /
 * {@code "ru"} values: {@code forLanguageTag("ru") ⇄ "ru"} and {@code forLanguageTag("pl") ⇄ "pl"}
 * round-trip unchanged, so no data migration is needed (Requirement 12.3).
 *
 * <p>Write path: {@code Locale.toLanguageTag()} lowercased; {@code null} is stored as {@code null}.
 * Read path: {@code Locale.forLanguageTag(...)}, resolving {@code null}, blank, or
 * unsupported/legacy stored values to the {@link #FALLBACK} (PL) — consistent with the existing
 * {@link com.foremen.service.EmailLocaleResolver} PL fallback (Requirements 12.1, 12.2).
 *
 * <p>The supported-locale set (PL/RU) and the PL {@link #FALLBACK} are centralized here so the
 * converter and future locale validation reuse one source of truth. Because {@link Locale} is
 * open-ended, "supported" is a validation concern (see {@link #isSupported(Locale)}), not a type
 * guarantee.
 */
@Converter(autoApply = false)
public class LocaleAttributeConverter implements AttributeConverter<Locale, String> {

    /** Polish locale — the base/fallback language. */
    public static final Locale PL = Locale.forLanguageTag("pl");

    /** Russian locale. */
    public static final Locale RU = Locale.forLanguageTag("ru");

    /** The application's supported-locale set (PL, RU). */
    public static final Set<Locale> SUPPORTED = Set.of(PL, RU);

    /**
     * The fallback locale (PL) used when a stored value is {@code null}, blank, or
     * unsupported/legacy (Requirement 12.2).
     */
    public static final Locale FALLBACK = PL;

    @Override
    public String convertToDatabaseColumn(Locale locale) {
        if (locale == null) {
            return null;
        }
        return locale.toLanguageTag().toLowerCase(Locale.ROOT);
    }

    @Override
    public Locale convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return FALLBACK;
        }
        Locale parsed = Locale.forLanguageTag(dbData.trim());
        return isSupported(parsed) ? parsed : FALLBACK;
    }

    /**
     * Whether the given locale is one of the application's {@link #SUPPORTED} locales (PL/RU),
     * matched by language (region/variant are ignored). {@code null} is never supported.
     *
     * @param locale the locale to test; may be {@code null}
     * @return {@code true} when the locale's language is {@code "pl"} or {@code "ru"}
     */
    public static boolean isSupported(Locale locale) {
        if (locale == null) {
            return false;
        }
        String language = locale.getLanguage();
        return SUPPORTED.stream().anyMatch(supported -> supported.getLanguage().equals(language));
    }
}
