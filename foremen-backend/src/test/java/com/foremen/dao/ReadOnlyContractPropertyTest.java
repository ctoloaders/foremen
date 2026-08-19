package com.foremen.dao;

import net.jqwik.api.*;
import net.jqwik.api.constraints.NotBlank;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Property-based tests for the Read-Only Contract Enforcement.
 *
 * <p><b>Property 2: Read-Only Contract Enforcement</b></p>
 * <p>For any method resolvable on a concrete interface that extends ReadOnlyAdminDao
 * (without also extending AdminDao, CrudRepository, or PagingAndSortingRepository),
 * the method name SHALL NOT match any write operation pattern.</p>
 *
 * <p><b>Validates: Requirements 1.11, 3.2</b></p>
 */
@Label("ReadOnlyAdminDao — Read-Only Contract Property Tests")
class ReadOnlyContractPropertyTest {

    private static final List<String> WRITE_METHOD_PATTERNS = List.of(
            "save", "saveAll", "delete", "deleteById", "deleteAll",
            "deleteAllById", "flush", "saveAndFlush"
    );

    private static final Set<String> READ_ONLY_DAO_METHOD_NAMES;

    static {
        // Collect all method names from ReadOnlyAdminDao (declared + inherited from Repository)
        READ_ONLY_DAO_METHOD_NAMES = Arrays.stream(ReadOnlyAdminDao.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
    }

    /**
     * Reflects all methods on ReadOnlyAdminDao (declared + inherited from Repository)
     * and verifies none match any write-method pattern.
     *
     * <p><b>Validates: Requirements 1.11, 3.2</b></p>
     */
    @Property(tries = 100)
    @Label("No actual method on ReadOnlyAdminDao matches write-method patterns")
    void noActualMethodMatchesWritePattern(@ForAll("writeMethodPatterns") String writePattern) {
        // For each write pattern, verify it does NOT appear among ReadOnlyAdminDao's methods
        boolean matchFound = READ_ONLY_DAO_METHOD_NAMES.stream()
                .anyMatch(methodName -> methodName.equals(writePattern) || methodName.startsWith(writePattern));

        assert !matchFound :
                "ReadOnlyAdminDao must NOT have a method matching write pattern '" + writePattern +
                        "'. Found methods: " + READ_ONLY_DAO_METHOD_NAMES;
    }

    /**
     * Generates random strings matching write-method patterns and verifies they do not
     * appear in the interface's method names.
     *
     * <p><b>Validates: Requirements 1.11, 3.2</b></p>
     */
    @Property(tries = 100)
    @Label("Generated write-method names do not appear on ReadOnlyAdminDao")
    void generatedWriteMethodNamesAbsentFromInterface(
            @ForAll("generatedWriteMethodNames") String generatedWriteMethodName) {

        boolean existsOnInterface = READ_ONLY_DAO_METHOD_NAMES.contains(generatedWriteMethodName);

        assert !existsOnInterface :
                "ReadOnlyAdminDao must NOT contain method '" + generatedWriteMethodName +
                        "'. This matches a write-method pattern. Found methods: " + READ_ONLY_DAO_METHOD_NAMES;
    }

    /**
     * Verifies that all declared methods on ReadOnlyAdminDao have names that do NOT
     * start with any write-method prefix.
     *
     * <p><b>Validates: Requirements 1.11, 3.2</b></p>
     */
    @Property(tries = 100)
    @Label("All ReadOnlyAdminDao methods are read-only (no write prefix)")
    void allDeclaredMethodsAreReadOnly(@ForAll("declaredMethodNames") String methodName) {
        for (String writePattern : WRITE_METHOD_PATTERNS) {
            assert !methodName.startsWith(writePattern) :
                    "Method '" + methodName + "' starts with write pattern '" + writePattern + "'";
        }
    }

    // --- Providers ---

    @Provide
    Arbitrary<String> writeMethodPatterns() {
        return Arbitraries.of(WRITE_METHOD_PATTERNS);
    }

    @Provide
    Arbitrary<String> generatedWriteMethodNames() {
        // Generate random method names that match write-method patterns
        // e.g. "saveEntity", "deleteAllRecords", "flushCache", etc.
        Arbitrary<String> suffixes = Arbitraries.strings()
                .alpha()
                .ofMinLength(0)
                .ofMaxLength(20);

        Arbitrary<String> prefixes = Arbitraries.of(WRITE_METHOD_PATTERNS);

        return Combinators.combine(prefixes, suffixes)
                .as((prefix, suffix) -> prefix + capitalize(suffix));
    }

    @Provide
    Arbitrary<String> declaredMethodNames() {
        List<String> methodNames = Arrays.stream(ReadOnlyAdminDao.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();
        return Arbitraries.of(methodNames);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
