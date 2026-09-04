package com.foremen.config.security;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import java.util.Set;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for the client TTL {@code @Positive} validation predicate on
 * {@link JwtProperties} (Requirement 7.5).
 *
 * <p>The design specifies testing the validation predicate directly: rather than booting a
 * Spring context, this drives a plain jakarta {@link Validator} over {@link JwtProperties}
 * instances that vary only the two client TTL fields
 * ({@code clientAccessTtlMinutes} / {@code clientRefreshTtlDays}), holding the employee TTLs
 * and secret at valid values. Because {@link JwtProperties}' compact constructor substitutes
 * defaults for {@code null}, the tests construct instances via the 5-arg canonical constructor
 * with explicit non-null client TTL values so the {@code @Positive} constraint is actually
 * exercised on the supplied number.
 *
 * Property 14: Client TTL validation predicate — Validates: Requirements 7.5
 */
@Tag("Feature: FOR-03-05-otp-client-auth, Property 14: Client TTL validation predicate")
class JwtClientTtlValidationPropertyTest {

    private static final String SECRET = "a-test-signing-secret-at-least-32-bytes-long";
    private static final int VALID_ACCESS_TTL = 30;
    private static final int VALID_REFRESH_TTL = 7;

    private final Validator validator;

    JwtClientTtlValidationPropertyTest() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            this.validator = factory.getValidator();
        }
    }

    private JwtProperties props(Integer clientAccessTtlMinutes, Integer clientRefreshTtlDays) {
        return new JwtProperties(
                VALID_ACCESS_TTL,
                VALID_REFRESH_TTL,
                clientAccessTtlMinutes,
                clientRefreshTtlDays,
                SECRET);
    }

    // Feature: FOR-03-05-otp-client-auth, Property 14: Client TTL validation predicate
    // For all integers that are zero or negative, the clientAccessTtlMinutes @Positive predicate
    // rejects the value (a constraint violation is reported on that field).
    // Validates: Requirements 7.5
    @Property(tries = 100)
    void nonPositiveClientAccessTtlIsRejected(@ForAll("nonPositiveInts") int clientAccessTtlMinutes) {
        Set<ConstraintViolation<JwtProperties>> violations =
                validator.validate(props(clientAccessTtlMinutes, VALID_REFRESH_TTL));

        assertThat(violations)
                .as("non-positive clientAccessTtlMinutes must be rejected")
                .isNotEmpty()
                .anyMatch(v -> v.getPropertyPath().toString().equals("clientAccessTtlMinutes"));
    }

    // Feature: FOR-03-05-otp-client-auth, Property 14: Client TTL validation predicate
    // For all integers that are zero or negative, the clientRefreshTtlDays @Positive predicate
    // rejects the value (a constraint violation is reported on that field).
    // Validates: Requirements 7.5
    @Property(tries = 100)
    void nonPositiveClientRefreshTtlIsRejected(@ForAll("nonPositiveInts") int clientRefreshTtlDays) {
        Set<ConstraintViolation<JwtProperties>> violations =
                validator.validate(props(VALID_ACCESS_TTL, clientRefreshTtlDays));

        assertThat(violations)
                .as("non-positive clientRefreshTtlDays must be rejected")
                .isNotEmpty()
                .anyMatch(v -> v.getPropertyPath().toString().equals("clientRefreshTtlDays"));
    }

    // Feature: FOR-03-05-otp-client-auth, Property 14: Client TTL validation predicate
    // For all pairs of zero-or-negative client TTLs, both fields are rejected together.
    // Validates: Requirements 7.5
    @Property(tries = 100)
    void nonPositiveClientTtlPairIsRejected(
            @ForAll("nonPositiveInts") int clientAccessTtlMinutes,
            @ForAll("nonPositiveInts") int clientRefreshTtlDays) {
        Set<ConstraintViolation<JwtProperties>> violations =
                validator.validate(props(clientAccessTtlMinutes, clientRefreshTtlDays));

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("clientAccessTtlMinutes"));
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("clientRefreshTtlDays"));
    }

    // Feature: FOR-03-05-otp-client-auth, Property 14: Client TTL validation predicate
    // For all positive integers, both client TTL @Positive predicates accept the value
    // (no constraint violations on the client TTL fields).
    // Validates: Requirements 7.5
    @Property(tries = 100)
    void positiveClientTtlsAreAccepted(
            @ForAll @IntRange(min = 1, max = Integer.MAX_VALUE) int clientAccessTtlMinutes,
            @ForAll @IntRange(min = 1, max = Integer.MAX_VALUE) int clientRefreshTtlDays) {
        Set<ConstraintViolation<JwtProperties>> violations =
                validator.validate(props(clientAccessTtlMinutes, clientRefreshTtlDays));

        assertThat(violations)
                .as("positive client TTLs must produce no client-TTL constraint violations")
                .noneMatch(v -> v.getPropertyPath().toString().equals("clientAccessTtlMinutes"))
                .noneMatch(v -> v.getPropertyPath().toString().equals("clientRefreshTtlDays"));
    }

    // --- Providers ---

    /** Zero and all negative ints down to Integer.MIN_VALUE (the @Positive rejection domain). */
    @Provide
    Arbitrary<Integer> nonPositiveInts() {
        return Arbitraries.integers().between(Integer.MIN_VALUE, 0);
    }
}
