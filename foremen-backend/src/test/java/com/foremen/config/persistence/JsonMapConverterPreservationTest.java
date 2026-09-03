package com.foremen.config.persistence;

import net.jqwik.api.*;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Preservation Property Tests for JsonMapConverter (Property 2: Preservation).
 *
 * These tests capture EXISTING valid behavior that MUST remain unchanged after the fix for
 * BUG 1.7 (jsonb persistence type). They follow the observation-first methodology: the
 * behaviors below were observed on the UNFIXED code and MUST continue to hold.
 *
 * On UNFIXED code, these tests are EXPECTED TO PASS — passing confirms the baseline behavior
 * we want to preserve.
 *
 * Observed baseline behavior:
 * - convertToDatabaseColumn(null) returns null
 * - convertToEntityAttribute(null) returns null
 * - Round-trip of any valid JSON map preserves equality (serialize -> deserialize = identity)
 *
 * The round-trip is invoked reflectively so the test is robust across the BUG 1.7 fix, which
 * changes convertToDatabaseColumn's return type from String to Object (PGobject). The value
 * produced by convertToDatabaseColumn is always fed straight back into convertToEntityAttribute,
 * regardless of whether it is a String (unfixed) or a PGobject (fixed).
 *
 * Validates: Requirements 3.4, 3.5
 */
@Tag("Feature: FOR-02-07-users-ui-fixes, Property 2: Preservation")
class JsonMapConverterPreservationTest {

    private final JsonMapConverter converter = new JsonMapConverter();

    // --- Preservation: null inputs map to null on both directions ---

    /**
     * Property: convertToDatabaseColumn(null) returns null (null bypass preserved).
     *
     * Validates: Requirements 3.4
     */
    @Property(tries = 5)
    void convertToDatabaseColumn_null_returnsNull() {
        assertNull(converter.convertToDatabaseColumn(null),
                "convertToDatabaseColumn(null) should return null");
    }

    /**
     * Property: convertToEntityAttribute(null) returns null (null bypass preserved).
     *
     * Validates: Requirements 3.4
     */
    @Property(tries = 5)
    void convertToEntityAttribute_null_returnsNull() {
        assertNull(converter.convertToEntityAttribute(null),
                "convertToEntityAttribute(null) should return null");
    }

    // --- Preservation: round-trip preserves equality for any valid JSON map ---

    /**
     * Property: For any non-null Map<String, Object> with valid JSON-serializable values,
     * deserialize(serialize(map)) equals the original map.
     *
     * Validates: Requirements 3.5
     */
    @Property(tries = 50)
    void roundTrip_preservesEquality(@ForAll("jsonMaps") Map<String, Object> map) throws Exception {
        Object serialized = converter.convertToDatabaseColumn(map);
        assertNotNull(serialized, "Serialized form of a non-null map should not be null");

        Map<String, Object> roundTripped = invokeConvertToEntityAttribute(serialized);

        assertEquals(map, roundTripped,
                "Round-trip (serialize -> deserialize) must preserve the original map");
    }

    /**
     * Invokes convertToEntityAttribute reflectively so the call works whether the parameter type
     * is String (unfixed) or Object (fixed for BUG 1.7).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeConvertToEntityAttribute(Object dbValue) throws Exception {
        Method target = null;
        for (Method m : JsonMapConverter.class.getDeclaredMethods()) {
            if (m.getName().equals("convertToEntityAttribute") && m.getParameterCount() == 1
                    && m.getReturnType().equals(Map.class)) {
                target = m;
                break;
            }
        }
        assertNotNull(target, "convertToEntityAttribute(single-arg) should exist");
        target.setAccessible(true);
        return (Map<String, Object>) target.invoke(converter, dbValue);
    }

    @Provide
    Arbitrary<Map<String, Object>> jsonMaps() {
        Arbitrary<String> keys = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);
        Arbitrary<Object> values = Arbitraries.oneOf(
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50).map(s -> (Object) s),
                Arbitraries.integers().between(-1000, 1000).map(i -> (Object) i),
                Arbitraries.of(true, false).map(b -> (Object) b)
        );

        return Arbitraries.maps(keys, values).ofMinSize(0).ofMaxSize(5)
                .map(HashMap::new);
    }
}
