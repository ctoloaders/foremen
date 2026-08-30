package com.foremen.config.security.property;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.Tuple;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for bcrypt password hashing.
 *
 * Uses a real {@link BCryptPasswordEncoder} with cost factor 12 — the same
 * configuration exposed by {@code com.foremen.config.security.PasswordEncoderConfig}.
 *
 * Property 23: Bcrypt hash/verify round-trip — Validates: Requirements 10.3, 10.4
 * Property 24: Stored password is never plaintext — Validates: Requirements 10.2
 */
@Tag("Feature: FOR-03-01-jwt-auth, Property 23-24: bcrypt hashing")
class PasswordEncoderPropertyTest {

    private static final int BCRYPT_COST_FACTOR = 12;

    /**
     * A $2a$ bcrypt hash at cost 12 has the shape:
     * {@code $2a$12$<22-char base64 salt><31-char base64 checksum>}.
     */
    private static final String BCRYPT_2A_12_PATTERN =
            "^\\$2a\\$12\\$[./A-Za-z0-9]{53}$";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCRYPT_COST_FACTOR);

    // Feature: FOR-03-01-jwt-auth, Property 23: Bcrypt hash/verify round-trip
    // For all passwords p and any password q distinct from p, verifying p against
    // bcrypt(p, cost=12) reports a match, and verifying q against bcrypt(p) reports no match.
    // Validates: Requirements 10.3, 10.4
    @Property(tries = 100)
    void bcryptHashVerifyRoundTrip(@ForAll("distinctPasswordPairs") Tuple.Tuple2<String, String> pair) {
        String p = pair.get1();
        String q = pair.get2();

        String hash = encoder.encode(p);

        assertThat(encoder.matches(p, hash))
                .as("bcrypt(p) must verify the original password p")
                .isTrue();
        assertThat(encoder.matches(q, hash))
                .as("bcrypt(p) must not verify a distinct password q")
                .isFalse();
        assertThat(hash)
                .as("hash must be a $2a$12$ bcrypt string")
                .matches(BCRYPT_2A_12_PATTERN);
    }

    // Feature: FOR-03-01-jwt-auth, Property 24: Stored password is never plaintext
    // For all passwords, the value persisted to passwordHash differs from the plaintext
    // password and is a valid bcrypt hash string.
    // Validates: Requirements 10.2
    @Property(tries = 100)
    void storedPasswordIsNeverPlaintext(@ForAll("passwords") String password) {
        String hash = encoder.encode(password);

        assertThat(hash)
                .as("stored hash must differ from the plaintext password")
                .isNotEqualTo(password);
        assertThat(hash)
                .as("stored hash must be a valid $2a$12$ bcrypt string")
                .matches(BCRYPT_2A_12_PATTERN);
        // Sanity: the produced hash is still a working bcrypt hash for the password.
        assertThat(encoder.matches(password, hash)).isTrue();
    }

    // --- Providers ---

    /**
     * Passwords across a broad input space. BCrypt rejects inputs whose UTF-8 encoding
     * exceeds 72 bytes, so we filter to that documented limit; a minimum length of 1
     * avoids the empty-string edge that carries no information for these properties.
     * Characters may be multi-byte, so the filter is on the encoded byte length rather
     * than the character count.
     */
    @Provide
    Arbitrary<String> passwords() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(72)
                .filter(s -> s.getBytes(StandardCharsets.UTF_8).length <= 72);
    }

    /**
     * Pairs of distinct passwords (q != p) for the round-trip negative case.
     */
    @Provide
    Arbitrary<Tuple.Tuple2<String, String>> distinctPasswordPairs() {
        return Combinators.combine(passwords(), passwords())
                .as(Tuple::of)
                .filter(t -> !t.get1().equals(t.get2()));
    }
}
