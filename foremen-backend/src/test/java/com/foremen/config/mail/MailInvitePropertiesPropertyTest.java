package com.foremen.config.mail;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: FOR-03-02-user-invitation, Property 18: Invite base URL validation predicate
 *
 * For all strings that are empty, blank, or not a syntactically valid absolute HTTP or HTTPS
 * URL, {@link MailInviteProperties#isInviteBaseUrlValid()} SHALL return false; for all
 * syntactically valid absolute http/https URLs it SHALL return true.
 *
 * Validates: Requirements 7.2
 */
@Tag("Feature: FOR-03-02-user-invitation, Property 18: Invite base URL validation predicate")
class MailInvitePropertiesPropertyTest {

    private static boolean predicate(String baseUrl) {
        return new MailInviteProperties(baseUrl).isInviteBaseUrlValid();
    }

    // Feature: FOR-03-02-user-invitation, Property 18: valid absolute http/https URLs accepted
    // For all syntactically valid absolute http/https URLs, the predicate returns true.
    @Property(tries = 100)
    void acceptsAbsoluteHttpAndHttpsUrls(@ForAll("validBaseUrls") String baseUrl) {
        assertThat(predicate(baseUrl))
                .as("expected valid absolute http/https URL to be accepted: %s", baseUrl)
                .isTrue();
    }

    // Feature: FOR-03-02-user-invitation, Property 18: empty/blank strings rejected
    // For all empty or whitespace-only strings (and null), the predicate returns false.
    @Property(tries = 100)
    void rejectsBlankStrings(@ForAll("blankStrings") String blank) {
        assertThat(predicate(blank))
                .as("expected blank base URL to be rejected: [%s]", blank)
                .isFalse();
    }

    // Feature: FOR-03-02-user-invitation, Property 18: non-http(s) / non-absolute URLs rejected
    // For all strings that are not syntactically valid absolute http/https URLs, the predicate
    // returns false (relative paths, non-http schemes, malformed URIs).
    @Property(tries = 100)
    void rejectsNonHttpOrNonAbsoluteUrls(@ForAll("invalidBaseUrls") String baseUrl) {
        assertThat(predicate(baseUrl))
                .as("expected non-absolute / non-http(s) value to be rejected: %s", baseUrl)
                .isFalse();
    }

    @Provide
    Arbitrary<String> validBaseUrls() {
        Arbitrary<String> scheme = Arbitraries.of("http", "https", "HTTP", "HTTPS", "Http", "Https");
        Arbitrary<String> host = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(20);
        Arbitrary<String> path = Arbitraries.of(
                "", "/", "/auth/set-password", "/a/b/c", "/set?x=1", "/p#frag");
        return Combinators.combine(scheme, host, path)
                .as((s, h, p) -> s + "://" + h + ".example.com" + p);
    }

    @Provide
    Arbitrary<String> blankStrings() {
        // Includes null so the null-guard branch is exercised.
        Arbitrary<String> whitespace = Arbitraries.of("", " ", "   ", "\t", "\n", " \t \n ");
        return whitespace.injectNull(0.15);
    }

    @Provide
    Arbitrary<String> invalidBaseUrls() {
        // Relative paths, opaque/other-scheme URIs, and malformed URIs that are not absolute
        // http/https URLs. All non-blank so they are distinct from the blankStrings provider.
        return Arbitraries.of(
                "example.com",
                "www.example.com/auth",
                "/auth/set-password",
                "auth/set-password",
                "ftp://example.com/file",
                "file:///etc/passwd",
                "mailto:user@example.com",
                "ws://example.com/socket",
                "ldap://example.com",
                "javascript:alert(1)",
                "http//missing-colon.com",
                "://no-scheme.com",
                "ht tp://space.com",
                "http://exa mple.com/bad space",
                "urn:isbn:0451450523",
                "tel:+123456789"
        );
    }
}
