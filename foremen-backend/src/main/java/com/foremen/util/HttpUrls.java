package com.foremen.util;

import java.net.URI;
import java.util.function.Supplier;

/** Shared validation of absolute http / https URLs entered by users (FOR-11-06 R9.1). */
public final class HttpUrls {

    /** Maximum stored length of a user-entered link. */
    public static final int MAX_LENGTH = 1000;

    private HttpUrls() {
    }

    /**
     * Trims {@code raw}; blank becomes {@code null}. Otherwise the value must be an absolute {@code http} or
     * {@code https} URL with a host and at most {@link #MAX_LENGTH} characters, else {@code onInvalid} is thrown.
     */
    public static String requireHttpUrl(String raw, Supplier<? extends RuntimeException> onInvalid) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String url = raw.trim();
        if (url.length() > MAX_LENGTH) {
            throw onInvalid.get();
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            boolean httpScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            if (!httpScheme || uri.getHost() == null || uri.getHost().isBlank()) {
                throw onInvalid.get();
            }
        } catch (IllegalArgumentException e) {
            throw onInvalid.get();
        }
        return url;
    }
}
