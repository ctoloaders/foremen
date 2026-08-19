package com.foremen.dao;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Arbitraries;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Property-based test verifying that AdminDao is a superset of ReadOnlyAdminDao.
 *
 * <p><b>Validates: Requirements 3.1</b></p>
 *
 * <p>Property 1: For any method declared in ReadOnlyAdminDao, that method SHALL be
 * resolvable (accessible via reflection) on AdminDao. In other words, AdminDao inherits
 * all read operations from ReadOnlyAdminDao — the set of methods on AdminDao is a strict superset.</p>
 */
class DaoInterfaceContractPropertyTest {

    private static final Method[] READ_ONLY_METHODS = ReadOnlyAdminDao.class.getDeclaredMethods();

    @Provide
    Arbitrary<Method> readOnlyDaoMethods() {
        return Arbitraries.of(READ_ONLY_METHODS);
    }

    /**
     * Property 1: AdminDao is a Superset of ReadOnlyAdminDao.
     *
     * <p><b>Validates: Requirements 3.1</b></p>
     *
     * <p>For each method declared in ReadOnlyAdminDao, verify it is resolvable
     * (accessible) on AdminDao via {@code getMethod(name, parameterTypes)}.</p>
     */
    @Property(tries = 100)
    void adminDaoResolvesAllReadOnlyMethods(@ForAll("readOnlyDaoMethods") Method readOnlyMethod) {
        String methodName = readOnlyMethod.getName();
        Class<?>[] paramTypes = readOnlyMethod.getParameterTypes();

        assertThatNoException()
                .as("Method '%s(%s)' declared in ReadOnlyAdminDao must be resolvable on AdminDao",
                        methodName, paramTypesToString(paramTypes))
                .isThrownBy(() -> AdminDao.class.getMethod(methodName, paramTypes));
    }

    /**
     * Supplementary assertion: every ReadOnlyAdminDao declared method is present
     * in AdminDao's full method set (getMethods() includes inherited).
     *
     * <p><b>Validates: Requirements 3.1</b></p>
     */
    @Property(tries = 100)
    void adminDaoMethodSetContainsAllReadOnlyMethods(@ForAll("readOnlyDaoMethods") Method readOnlyMethod) {
        Method[] adminDaoMethods = AdminDao.class.getMethods();

        assertThat(adminDaoMethods)
                .as("AdminDao.getMethods() should contain method '%s' from ReadOnlyAdminDao",
                        readOnlyMethod.getName())
                .anyMatch(m -> m.getName().equals(readOnlyMethod.getName())
                        && java.util.Arrays.equals(m.getParameterTypes(), readOnlyMethod.getParameterTypes()));
    }

    private static String paramTypesToString(Class<?>[] paramTypes) {
        return java.util.Arrays.stream(paramTypes)
                .map(Class::getSimpleName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }
}
