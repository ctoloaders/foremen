package com.foremen.qa.report;

import com.foremen.qa.support.TestConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Redaction of secret values from any text that enters the report (Requirement 10.10).
 *
 * <p>The demo report embeds step text, captions, and error messages verbatim, so it must never leak
 * real credentials or tokens. This helper masks:
 * <ul>
 *   <li>the configured admin password ({@link TestConfig#adminPassword()}) — the one static secret
 *       the suite knows;</li>
 *   <li>any run-generated passwords registered at runtime via {@link #registerSecret(String)}
 *       (the test-user fixture hands its generated passwords here);</li>
 *   <li>bearer tokens and JWT-shaped strings and common {@code password"/"token"} JSON/kv fragments
 *       via structural patterns, so a token that slips into an error body is still masked.</li>
 * </ul>
 *
 * <p>Generated test data that is <em>not</em> secret (emails, dictionary codes, project names) is
 * intentionally left intact — the requirement explicitly allows it and it makes the demo readable.
 *
 * <p>Registered secrets are process-wide (a {@code static} set) because the redactor runs inside the
 * Cucumber plugin, which is instantiated by Cucumber rather than the DI container. All methods are
 * null-safe and thread-safe.
 */
public final class Secrets {

    private static final String MASK = "\u2022\u2022\u2022\u2022\u2022\u2022"; // "••••••"

    /** Run-registered literal secrets (generated passwords, etc.) to mask on sight. */
    private static final List<String> LITERALS = java.util.Collections.synchronizedList(new ArrayList<>());

    // Bearer <token>
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._\\-]+");
    // JWT-shaped: three base64url segments separated by dots
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9._\\-]{10,}");
    // "password":"...", "token":"...", access/refresh token fields (JSON or loose kv)
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i)(\"?(?:password|pass|pwd|token|access[_-]?token|refresh[_-]?token|authorization)\"?\\s*[:=]\\s*)"
                    + "(\"[^\"]*\"|'[^']*'|[^\\s,;}&]+)");

    private Secrets() {
    }

    /**
     * Register a literal secret value (e.g. a generated test-user password) to be masked wherever it
     * appears in report text. Blank/short values are ignored to avoid over-masking common substrings.
     */
    public static void registerSecret(String value) {
        if (value != null && value.length() >= 4 && !LITERALS.contains(value)) {
            LITERALS.add(value);
        }
    }

    /** Clear all run-registered secrets (used by tests). The admin password is always masked. */
    public static void reset() {
        LITERALS.clear();
    }

    /**
     * Return {@code text} with every known secret masked. Applies structural patterns first (bearer/
     * JWT/sensitive-field) then exact literals (the admin password and any registered generated
     * passwords). Returns {@code null} unchanged.
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;

        // Exact literals: the configured admin password + any run-registered secrets.
        String adminPwd = safeAdminPassword();
        if (adminPwd != null && adminPwd.length() >= 4) {
            out = out.replace(adminPwd, MASK);
        }
        // Copy to avoid holding the synchronized lock while replacing.
        List<String> literalsSnapshot;
        synchronized (LITERALS) {
            literalsSnapshot = new ArrayList<>(LITERALS);
        }
        for (String literal : literalsSnapshot) {
            out = out.replace(literal, MASK);
        }

        // Structural patterns catch tokens/fields even when the literal is unknown.
        out = maskSensitiveFields(out);
        out = BEARER.matcher(out).replaceAll("Bearer " + MASK);
        out = JWT.matcher(out).replaceAll(MASK);

        return out;
    }

    /**
     * Apply the sensitive-field pattern preserving the field name and separator while masking the
     * value. Done as a dedicated pass so the replacement can reference the captured prefix group.
     */
    private static String maskSensitiveFields(String text) {
        java.util.regex.Matcher m = SENSITIVE_FIELD.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String prefix = m.group(1);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(prefix + MASK));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String safeAdminPassword() {
        try {
            return TestConfig.adminPassword();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
