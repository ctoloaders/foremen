package com.foremen.service;

import com.foremen.config.persistence.LocaleAttributeConverter;

import java.util.Locale;

/**
 * Pure helper that maps a stored {@code UserEntity.locale} to the {@link Locale} used to render
 * invitation / OTP email subjects and bodies.
 *
 * <p>Only Polish and Russian are supported. Resolution is by language, case-insensitively: a
 * Russian locale resolves to {@link #RU}; every other value — including {@code null} or an
 * unsupported locale — falls back to {@link #PL} (Requirements 4.7, 8.4, 9.3, 12.4).
 *
 * <p>FOR-06-12 (R12.4): with {@code UserEntity.locale} migrated {@code String → Locale}, this
 * resolver takes a {@link Locale} directly (the stored value already resolved to PL/RU through
 * {@link LocaleAttributeConverter}). It delegates the PL/RU set and the PL fallback to that shared
 * converter so there is one source of truth. The class is stateless and side-effect free so it can
 * be unit- and property-tested in isolation and reused by the mail senders.
 */
public final class EmailLocaleResolver {

    /** Polish locale, the base/fallback language for emails. */
    public static final Locale PL = LocaleAttributeConverter.PL;

    /** Russian locale. */
    public static final Locale RU = LocaleAttributeConverter.RU;

    private EmailLocaleResolver() {
        // utility class; not instantiable
    }

    /**
     * Resolves the email locale from a user's stored {@link Locale}.
     *
     * @param userLocale the value of {@code UserEntity.getLocale()}; may be {@code null}
     * @return {@link #RU} when {@code userLocale}'s language is Russian, and {@link #PL} for every
     *         other value including {@code null} and unsupported locales
     */
    public static Locale resolve(Locale userLocale) {
        if (userLocale != null && RU.getLanguage().equals(userLocale.getLanguage())) {
            return RU;
        }
        return PL;
    }
}
