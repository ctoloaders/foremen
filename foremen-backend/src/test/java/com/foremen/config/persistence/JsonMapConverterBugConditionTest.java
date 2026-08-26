package com.foremen.config.persistence;

import net.jqwik.api.*;
import org.postgresql.util.PGobject;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug Condition Exploration Test for JsonMapConverter (BUG 1.7).
 *
 * This test encodes the EXPECTED (fixed) behavior:
 * - BUG 1.7: convertToDatabaseColumn(nonNullMap) SHOULD return a PGobject with type "jsonb"
 *
 * On UNFIXED code, this test is EXPECTED TO FAIL — failure confirms the bug exists.
 * Currently, convertToDatabaseColumn returns a plain String, which causes PostgreSQL to reject
 * the value because the column is typed jsonb but the JDBC driver sends it as VARCHAR.
 *
 * Validates: Requirements 1.7
 */
@Tag("Feature: FOR-02-07-users-ui-fixes, Property 1: Bug Condition")
class JsonMapConverterBugConditionTest {

    private final JsonMapConverter converter = new JsonMapConverter();

    /**
     * Property: For any non-null Map<String, Object> with valid JSON-serializable values,
     * convertToDatabaseColumn MUST return a PGobject with type "jsonb".
     * Currently FAILS because it returns a plain String.
     *
     * Validates: Requirements 1.7
     */
    @Property(tries = 50)
    void convertToDatabaseColumn_nonNullMap_shouldReturnPGobjectWithJsonbType(
            @ForAll("jsonMaps") Map<String, Object> map) {

        Object result = converter.convertToDatabaseColumn(map);

        assertNotNull(result, "Result should not be null for non-null input");
        assertInstanceOf(PGobject.class, result,
                "convertToDatabaseColumn should return PGobject, but got: " + result.getClass().getName());

        PGobject pgObject = (PGobject) result;
        assertEquals("jsonb", pgObject.getType(),
                "PGobject type should be 'jsonb'");
        assertNotNull(pgObject.getValue(),
                "PGobject value should not be null for non-null input");
    }

    @Provide
    Arbitrary<Map<String, Object>> jsonMaps() {
        Arbitrary<String> keys = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);
        Arbitrary<Object> values = Arbitraries.oneOf(
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50).map(s -> (Object) s),
                Arbitraries.integers().between(-1000, 1000).map(i -> (Object) i),
                Arbitraries.of(true, false).map(b -> (Object) b)
        );

        return Arbitraries.maps(keys, values).ofMinSize(1).ofMaxSize(5);
    }
}
