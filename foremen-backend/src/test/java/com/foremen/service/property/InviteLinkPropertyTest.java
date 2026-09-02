package com.foremen.service.property;

// Feature: FOR-03-02-user-invitation, Property 7: Invite-link construction round-trips the token

import com.foremen.service.InviteLinkBuilder;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 7: Invite-link construction round-trips the token.
 *
 * <p>For all configured base URLs and all token values, the constructed Invite_Link SHALL equal
 * the base URL followed by {@code ?token={encoded}} when the base URL contains no {@code ?}, or
 * {@code &token={encoded}} when it contains a {@code ?}, where {@code {encoded}} is the
 * URL-encoded token; and URL-decoding the appended {@code token} parameter SHALL yield the
 * original token value.
 *
 * <p><b>Validates: Requirements 4.3, 7.3, 7.4</b>
 */
class InviteLinkPropertyTest {

    @Property(tries = 100)
    void separatorIsQuestionMarkWhenBaseUrlHasNoQuery(
            @ForAll("baseUrlsWithoutQuery") String baseUrl,
            @ForAll("tokens") String token) {

        String link = InviteLinkBuilder.buildInviteLink(baseUrl, token);

        String encoded = URLEncode(token);
        assertThat(link).isEqualTo(baseUrl + "?token=" + encoded);
    }

    @Property(tries = 100)
    void separatorIsAmpersandWhenBaseUrlAlreadyHasQuery(
            @ForAll("baseUrlsWithQuery") String baseUrl,
            @ForAll("tokens") String token) {

        String link = InviteLinkBuilder.buildInviteLink(baseUrl, token);

        String encoded = URLEncode(token);
        assertThat(link).isEqualTo(baseUrl + "&token=" + encoded);
    }

    @Property(tries = 100)
    void tokenRoundTripsThroughUrlDecoding(
            @ForAll("anyBaseUrl") String baseUrl,
            @ForAll("tokens") String token) {

        String link = InviteLinkBuilder.buildInviteLink(baseUrl, token);

        // Extract the value of the appended token parameter and decode it back.
        int idx = link.indexOf("token=");
        String appendedValue = link.substring(idx + "token=".length());
        String decoded = URLDecoder.decode(appendedValue, StandardCharsets.UTF_8);

        assertThat(decoded).isEqualTo(token);
    }

    @Property(tries = 100)
    void linkStartsWithBaseUrlAndSeparatorMatchesQueryPresence(
            @ForAll("anyBaseUrl") String baseUrl,
            @ForAll("tokens") String token) {

        String link = InviteLinkBuilder.buildInviteLink(baseUrl, token);

        assertThat(link).startsWith(baseUrl);
        String separator = link.substring(baseUrl.length(), baseUrl.length() + 1);
        if (baseUrl.indexOf('?') >= 0) {
            assertThat(separator).isEqualTo("&");
        } else {
            assertThat(separator).isEqualTo("?");
        }
    }

    private static String URLEncode(String token) {
        return java.net.URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    @Provide
    Arbitrary<String> baseUrlsWithoutQuery() {
        return Arbitraries.of(
                "http://localhost:3000/auth/set-password",
                "https://app.foremen.com/auth/set-password",
                "https://example.org/invite",
                "http://127.0.0.1:8080/set-password"
        );
    }

    @Provide
    Arbitrary<String> baseUrlsWithQuery() {
        return Arbitraries.of(
                "http://localhost:3000/auth/set-password?lang=pl",
                "https://app.foremen.com/auth/set-password?ref=email",
                "https://example.org/invite?a=1&b=2",
                "http://127.0.0.1:8080/set-password?x="
        );
    }

    @Provide
    Arbitrary<String> anyBaseUrl() {
        return Arbitraries.oneOf(baseUrlsWithoutQuery(), baseUrlsWithQuery());
    }

    @Provide
    Arbitrary<String> tokens() {
        // Arbitrary token strings: canonical UUIDs plus arbitrary strings containing
        // characters that require URL-encoding (spaces, &, =, ?, +, /, unicode, etc.).
        Arbitrary<String> uuids = Arbitraries.create(java.util.UUID::randomUUID).map(java.util.UUID::toString);
        Arbitrary<String> arbitrary = Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(64);
        return Arbitraries.oneOf(uuids, arbitrary);
    }
}
