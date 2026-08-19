package com.foremen.service.query.property;

import com.foremen.dao.ReadOnlyAdminDao;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.ReadOnlyAdminService;
import jakarta.persistence.EntityManager;
import net.jqwik.api.*;
import net.jqwik.api.Combinators;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Property 4: I18n Filter Field Resolution
 *
 * For any filter field string, for any set of i18n-supported properties, and for any locale suffix:
 * - If the field (or the final segment of a dot-notation field) is in the i18n set → resolved field appends suffix
 * - If the field (or the final segment) is NOT in the i18n set → resolved field equals the input unchanged
 * - Operator and value are never modified by i18n resolution
 *
 * Validates: Requirements 5.1 (i18n context)
 */
class I18nFilterResolutionPropertyTest {

    /**
     * Minimal implementation of ReadOnlyAdminService to test the default resolveI18nFilterField method.
     */
    private static final ReadOnlyAdminService<Object, Object, Object, Long> SERVICE = new ReadOnlyAdminService<>() {
        @Override
        public ServiceToDaoMapper<Object, Object, Object> getMapper() {
            return null;
        }

        @Override
        public ReadOnlyAdminDao<Object, Long> getReadDao() {
            return null;
        }

        @Override
        public EntityManager getEntityManager() {
            return null;
        }
    };

    // --- Property: Simple i18n field gets suffix appended ---

    @Property(tries = 100)
    @Label("Simple i18n field: if field is in i18nProperties, result is field + localeSuffix")
    void simpleI18nFieldGetsSuffix(
            @ForAll("simpleFieldNames") String field,
            @ForAll("localeSuffixes") String localeSuffix
    ) {
        Set<String> i18nProperties = Set.of(field);

        String result = SERVICE.resolveI18nFilterField(field, i18nProperties, localeSuffix);

        assert result.equals(field + localeSuffix) :
                "Expected '" + field + localeSuffix + "' but got '" + result + "'";
    }

    // --- Property: Dot-notation field with final segment in i18n gets suffix on final segment ---

    @Property(tries = 100)
    @Label("Dot-notation i18n: if final segment is in i18nProperties, result is prefix.finalSegment + localeSuffix")
    void dotNotationI18nFieldGetsSuffix(
            @ForAll("dotNotationFields") String field,
            @ForAll("localeSuffixes") String localeSuffix
    ) {
        int lastDot = field.lastIndexOf('.');
        String finalSegment = field.substring(lastDot + 1);
        String prefix = field.substring(0, lastDot);
        Set<String> i18nProperties = Set.of(finalSegment);

        String result = SERVICE.resolveI18nFilterField(field, i18nProperties, localeSuffix);

        String expected = prefix + "." + finalSegment + localeSuffix;
        assert result.equals(expected) :
                "Expected '" + expected + "' but got '" + result + "'";
    }

    // --- Property: Non-i18n simple field remains unchanged ---

    @Property(tries = 100)
    @Label("Non-i18n simple field: if field is NOT in i18nProperties, result equals input field unchanged")
    void nonI18nSimpleFieldUnchanged(
            @ForAll("simpleFieldNames") String field,
            @ForAll("simpleFieldNames") String otherField,
            @ForAll("localeSuffixes") String localeSuffix
    ) {
        Assume.that(!field.equals(otherField));

        // i18nProperties contains 'otherField' but NOT 'field'
        Set<String> i18nProperties = Set.of(otherField);

        String result = SERVICE.resolveI18nFilterField(field, i18nProperties, localeSuffix);

        assert result.equals(field) :
                "Expected unchanged '" + field + "' but got '" + result + "'";
    }

    // --- Property: Non-i18n dot-notation field remains unchanged ---

    @Property(tries = 100)
    @Label("Non-i18n dot-notation: if final segment is NOT in i18nProperties, result equals input field unchanged")
    void nonI18nDotNotationFieldUnchanged(
            @ForAll("dotNotationFields") String field,
            @ForAll("simpleFieldNames") String otherField,
            @ForAll("localeSuffixes") String localeSuffix
    ) {
        int lastDot = field.lastIndexOf('.');
        String finalSegment = field.substring(lastDot + 1);
        Assume.that(!finalSegment.equals(otherField));

        // i18nProperties contains 'otherField' but NOT the final segment of 'field'
        Set<String> i18nProperties = Set.of(otherField);

        String result = SERVICE.resolveI18nFilterField(field, i18nProperties, localeSuffix);

        assert result.equals(field) :
                "Expected unchanged '" + field + "' but got '" + result + "'";
    }

    // --- Property: Empty i18n properties → field always unchanged ---

    @Property(tries = 100)
    @Label("Empty i18nProperties: field always returned unchanged")
    void emptyI18nPropertiesFieldUnchanged(
            @ForAll("allFieldNames") String field,
            @ForAll("localeSuffixes") String localeSuffix
    ) {
        String result = SERVICE.resolveI18nFilterField(field, Set.of(), localeSuffix);

        assert result.equals(field) :
                "Expected unchanged '" + field + "' but got '" + result + "'";
    }

    // --- Property: Null i18n properties → field always unchanged ---

    @Property(tries = 100)
    @Label("Null i18nProperties: field always returned unchanged")
    void nullI18nPropertiesFieldUnchanged(
            @ForAll("allFieldNames") String field,
            @ForAll("localeSuffixes") String localeSuffix
    ) {
        String result = SERVICE.resolveI18nFilterField(field, null, localeSuffix);

        assert result.equals(field) :
                "Expected unchanged '" + field + "' but got '" + result + "'";
    }

    // --- Generators ---

    @Provide
    Arbitrary<String> simpleFieldNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(2)
                .ofMaxLength(12);
    }

    @Provide
    Arbitrary<String> dotNotationFields() {
        Arbitrary<String> segment = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(2)
                .ofMaxLength(8);

        Arbitrary<String> twoSegments = Combinators.combine(segment, segment)
                .as((a, b) -> a + "." + b);

        Arbitrary<String> threeSegments = Combinators.combine(segment, segment, segment)
                .as((a, b, c) -> a + "." + b + "." + c);

        return Arbitraries.oneOf(twoSegments, threeSegments);
    }

    @Provide
    Arbitrary<String> allFieldNames() {
        return Arbitraries.oneOf(simpleFieldNames(), dotNotationFields());
    }

    @Provide
    Arbitrary<String> localeSuffixes() {
        return Arbitraries.of("RU", "PL");
    }
}
