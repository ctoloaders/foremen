package com.foremen.exception.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.jqwik.api.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for ErrorResponse JSON serialization round-trip.
 * Uses jqwik 1.9.2 for property testing.
 */
class ErrorResponsePropertyTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /**
     * Property 7: ErrorResponse JSON Serialization Round-Trip
     *
     * For any randomly generated ErrorResponse instance, serializing to JSON via Jackson
     * and deserializing back SHALL produce an object equal to the original.
     *
     * Validates: Requirements 2.8
     */
    @Property(tries = 100)
    @Tag("Feature: FOR-01-03-exception, Property 7: ErrorResponse JSON Serialization Round-Trip")
    void errorResponseJsonRoundTrip(
            @ForAll("errorResponses") ErrorResponse original) throws Exception {

        // Act
        String json = objectMapper.writeValueAsString(original);
        ErrorResponse deserialized = objectMapper.readValue(json, ErrorResponse.class);

        // Assert
        assertThat(deserialized).isEqualTo(original);
    }

    @Provide
    Arbitrary<ErrorResponse> errorResponses() {
        Arbitrary<Instant> timestamps = Arbitraries.longs()
                .between(0L, 4_102_444_800_000L) // Up to year ~2100
                .map(Instant::ofEpochMilli);

        Arbitrary<Integer> statusCodes = Arbitraries.integers().between(100, 599);

        Arbitrary<String> nonEmptyStrings = Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(50);

        Arbitrary<Map<String, String>> fieldErrorsMaps = Arbitraries.maps(
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50)
        ).ofMinSize(1).ofMaxSize(5);

        Arbitrary<Map<String, String>> nullableFieldErrors = Arbitraries.oneOf(
                Arbitraries.just(null),
                fieldErrorsMaps
        );

        return Combinators.combine(timestamps, statusCodes, nonEmptyStrings, nonEmptyStrings, nonEmptyStrings, nullableFieldErrors)
                .as((timestamp, status, error, message, path, fieldErrors) ->
                        new ErrorResponse(timestamp, status, error, message, path, fieldErrors));
    }
}
