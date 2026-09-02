package com.foremen.service;

import com.foremen.dao.RoleDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.exception.ForemenApiException;
import net.jqwik.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Bug Condition Exploration Tests for UserService (BUG 1.4 and BUG 1.6).
 *
 * These tests encode the EXPECTED (fixed) behavior:
 * - BUG 1.4: validateLocale("en") SHOULD throw ForemenApiException with BAD_REQUEST
 * - BUG 1.6: resolveRole(id) SHOULD throw ForemenApiException with CONFLICT when role.code == "ADMIN"
 *
 * On UNFIXED code, these tests are EXPECTED TO FAIL — failure confirms the bugs exist.
 *
 * Validates: Requirements 1.4, 1.6
 */
@Tag("Feature: FOR-02-07-users-ui-fixes, Property 1: Bug Condition")
class UserServiceBugConditionTest {

    private final UserService userService;
    private final RoleDao roleDao;

    UserServiceBugConditionTest() {
        // Create UserService with mock dependencies — we only need roleDao for BUG 1.6
        this.roleDao = mock(RoleDao.class);
        this.userService = new UserService(null, roleDao, null, null, null, null, null);
    }

    // --- BUG 1.4: validateLocale("en") should throw ForemenApiException with BAD_REQUEST ---

    /**
     * Property: For locale "en", validateLocale MUST throw ForemenApiException with status BAD_REQUEST.
     * Currently FAILS because "en" is in SUPPORTED_LOCALES and passes silently.
     *
     * Validates: Requirements 1.4
     */
    @Property(tries = 10)
    void validateLocale_en_shouldThrowBadRequest() throws Exception {
        Method validateLocale = UserService.class.getDeclaredMethod("validateLocale", String.class);
        validateLocale.setAccessible(true);

        ForemenApiException exception = assertThrows(ForemenApiException.class, () -> {
            try {
                validateLocale.invoke(userService, "en");
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof ForemenApiException fae) {
                    throw fae;
                }
                throw e;
            }
        });

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus(),
                "validateLocale('en') should reject with BAD_REQUEST");
    }

    // --- BUG 1.6: resolveRole should throw CONFLICT when role.code == "ADMIN" ---

    /**
     * Property: For any roleId that resolves to a role with code "ADMIN",
     * resolveRole MUST throw ForemenApiException with status CONFLICT.
     * Currently FAILS because resolveRole only checks existence, not the role code.
     *
     * Validates: Requirements 1.6
     */
    @Property(tries = 10)
    void resolveRole_adminRole_shouldThrowConflict(@ForAll("adminRoleIds") Long roleId) throws Exception {
        // Setup mock: roleDao.findById returns a role with code "ADMIN"
        RoleEntity adminRole = new RoleEntity();
        adminRole.setId(roleId);
        adminRole.setCode("ADMIN");
        adminRole.setNameRU("Администратор");
        adminRole.setNamePL("Administrator");
        adminRole.setSystem(true);
        when(roleDao.findById(roleId)).thenReturn(Optional.of(adminRole));

        Method resolveRole = UserService.class.getDeclaredMethod("resolveRole", Long.class);
        resolveRole.setAccessible(true);

        ForemenApiException exception = assertThrows(ForemenApiException.class, () -> {
            try {
                resolveRole.invoke(userService, roleId);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof ForemenApiException fae) {
                    throw fae;
                }
                throw e;
            }
        });

        assertEquals(HttpStatus.CONFLICT, exception.getStatus(),
                "resolveRole for ADMIN role should reject with CONFLICT");
    }

    @Provide
    Arbitrary<Long> adminRoleIds() {
        return Arbitraries.longs().between(1L, 1000L);
    }
}
