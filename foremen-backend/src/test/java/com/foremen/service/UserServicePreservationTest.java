package com.foremen.service;

import com.foremen.dao.RoleDao;
import com.foremen.dao.model.RoleEntity;
import net.jqwik.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Preservation Property Tests for UserService (Property 2: Preservation).
 *
 * These tests capture EXISTING valid behavior that MUST remain unchanged after the fixes for
 * BUG 1.4 (locale) and BUG 1.6 (admin role). They follow the observation-first methodology:
 * the behaviors below were observed on the UNFIXED code and MUST continue to hold.
 *
 * On UNFIXED code, these tests are EXPECTED TO PASS — passing confirms the baseline behavior
 * we want to preserve.
 *
 * Observed baseline behavior:
 * - validateLocale("ru") passes without error
 * - validateLocale("pl") passes without error
 * - resolveRole(clientRoleId) returns the role entity when role.code != "ADMIN"
 *
 * Validates: Requirements 3.2, 3.3
 */
@Tag("Feature: FOR-02-07-users-ui-fixes, Property 2: Preservation")
class UserServicePreservationTest {

    private final UserService userService;
    private final RoleDao roleDao;

    UserServicePreservationTest() {
        // Only roleDao is needed for resolveRole; other collaborators are unused by the
        // helper methods under test.
        this.roleDao = mock(RoleDao.class);
        this.userService = new UserService(null, roleDao, null, null, null, null, null);
    }

    // --- Preservation: validateLocale accepts supported locales "ru" and "pl" ---

    /**
     * Property: For every supported locale in {"ru", "pl"}, validateLocale does NOT throw.
     * This behavior is present on the unfixed code and must be preserved after removing "en".
     *
     * Validates: Requirements 3.2
     */
    @Property(tries = 10)
    void validateLocale_supportedLocales_doNotThrow(@ForAll("supportedLocales") String locale) throws Exception {
        Method validateLocale = UserService.class.getDeclaredMethod("validateLocale", String.class);
        validateLocale.setAccessible(true);

        assertDoesNotThrow(() -> {
            try {
                validateLocale.invoke(userService, locale);
            } catch (InvocationTargetException e) {
                // Surface the real cause so a genuine failure is reported, not the reflection wrapper.
                if (e.getCause() instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(e.getCause());
            }
        }, "validateLocale('" + locale + "') should not throw for a supported locale");
    }

    /**
     * Property: A null locale is treated as "not provided" and does NOT throw.
     * This lenient handling exists on the unfixed code and must be preserved.
     *
     * Validates: Requirements 3.2
     */
    @Property(tries = 5)
    void validateLocale_null_doesNotThrow() throws Exception {
        Method validateLocale = UserService.class.getDeclaredMethod("validateLocale", String.class);
        validateLocale.setAccessible(true);

        assertDoesNotThrow(() -> {
            try {
                validateLocale.invoke(userService, (Object) null);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(e.getCause());
            }
        }, "validateLocale(null) should not throw");
    }

    // --- Preservation: resolveRole returns the role for any non-ADMIN role ---

    /**
     * Property: For any roleId that resolves to a role whose code is NOT "ADMIN",
     * resolveRole returns that exact role entity without throwing.
     * This behavior is present on the unfixed code and must be preserved after adding the
     * admin-role guard.
     *
     * Validates: Requirements 3.3
     */
    @Property(tries = 30)
    void resolveRole_nonAdminRole_returnsRole(
            @ForAll("roleIds") Long roleId,
            @ForAll("nonAdminRoleCodes") String code) throws Exception {

        RoleEntity role = new RoleEntity();
        ReflectionTestUtils.setField(role, "id", roleId);
        role.setCode(code);
        role.setNameRU("Роль");
        role.setNamePL("Rola");
        when(roleDao.findById(roleId)).thenReturn(Optional.of(role));

        Method resolveRole = UserService.class.getDeclaredMethod("resolveRole", Long.class);
        resolveRole.setAccessible(true);

        Object result = resolveRole.invoke(userService, roleId);

        assertSame(role, result,
                "resolveRole should return the resolved non-ADMIN role entity unchanged");
    }

    @Provide
    Arbitrary<String> supportedLocales() {
        return Arbitraries.of("ru", "pl");
    }

    @Provide
    Arbitrary<Long> roleIds() {
        return Arbitraries.longs().between(1L, 1000L);
    }

    @Provide
    Arbitrary<String> nonAdminRoleCodes() {
        return Arbitraries.of("CLIENT", "MANAGER", "FOREMAN", "VIEWER", "SUPERVISOR", "OWNER")
                .filter(c -> !"ADMIN".equals(c));
    }
}
