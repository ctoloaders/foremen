package com.foremen.service;

import java.util.Locale;

/**
 * Pure helper that maps a stored {@code UserEntity.locale} string to the {@link Locale} used to
 * render invitation email subjects and bodies.
 *
 * <p>Only Polish and Russian are supported. Resolution is case-insensitive: {@code "RU"} (in any
 * case) resolves to {@link #RU}, {@code "PL"} (in any case) resolves to {@link #PL}, and every
 * other value — including {@code null}, empty, whitespace, or an unsupported code such as
 * {@code "en"} — falls back to {@link #PL} (Requirements 4.7, 8.4).
 *
 * <p>This class is stateless and side-effect free so it can be unit- and property-tested in
 * isolation and reused by both {@code InviteService} and the invitation mail sender.
 */
public final class EmailLocaleResolver {

    /** Polish locale, the base/fallback language for invitation emails. */
    public static final Locale PL = Locale.forLanguageTag("pl");

    /** Russian locale. */
    public static final Locale RU = Locale.forLanguageTag("ru");

    private EmailLocaleResolver() {
        // utility class; not instantiable
    }

    /**
     * Resolves the email locale from a stored user-locale string.
     *
     * @param storedLocale the value of {@code UserEntity.locale}; may be {@code null} or blank
     * @return {@link #RU} when {@code storedLocale} equals {@code "RU"} case-insensitively,
     *         {@link #PL} when it equals {@code "PL"} case-insensitively, and {@link #PL} for every
     *         other value including {@code null} and empty/blank strings
     */
    public static Locale resolve(String storedLocale) {
        if (storedLocale == null) {
            return PL;
        }
        String normalized = storedLocale.trim();
        if (normalized.equalsIgnoreCase("RU")) {
            return RU;
        }
        return PL;
    }
}
