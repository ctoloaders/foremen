package com.foremen.service.procurement.access;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.UserDao;
import com.foremen.service.ProjectAccessCache;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** FOR-11-00 task 4.2 — Requirements 1.5, 1.6, 9.2. */
class ProcurementProjectAccessTest {

    private final ProjectAccessCache cache = mock(ProjectAccessCache.class);
    private final UserDao userDao = mock(UserDao.class);
    private final ProjectDao projectDao = mock(ProjectDao.class);
    private final ProcurementProjectAccess access = new ProcurementProjectAccess(cache, userDao, projectDao);

    private static Authentication auth(String role) {
        return new UsernamePasswordAuthenticationToken("1", null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void buyerGetsAllProjectIds() {
        when(userDao.findRoleCodeById(7L)).thenReturn(Optional.of("BUYER"));
        when(projectDao.findAllIds()).thenReturn(List.of(1L, 2L, 3L));
        assertEquals(Set.of(1L, 2L, 3L), access.allowedProjectIds(7L));
        verify(cache, never()).get(anyLong());
    }

    @Test
    void nonBuyerRolesGetMembershipsOnly() {
        for (String role : List.of("ADMIN", "MANAGER", "FOREMAN", "WORKER", "CLIENT")) {
            when(userDao.findRoleCodeById(5L)).thenReturn(Optional.of(role));
            when(cache.get(5L)).thenReturn(Set.of(10L));
            assertEquals(Set.of(10L), access.allowedProjectIds(5L), role);
        }
        verify(projectDao, never()).findAllIds();
    }

    @Test
    void userWithoutMembershipsOrUnknownUserGetsEmptySet() {
        when(userDao.findRoleCodeById(5L)).thenReturn(Optional.of("FOREMAN"));
        when(cache.get(5L)).thenReturn(Set.of());
        assertTrue(access.allowedProjectIds(5L).isEmpty());
        when(userDao.findRoleCodeById(6L)).thenReturn(Optional.empty());
        when(cache.get(6L)).thenReturn(null);
        assertTrue(access.allowedProjectIds(6L).isEmpty());
        assertTrue(access.allowedProjectIds(null).isEmpty());
    }

    /** FOR-11-03 R20.1. The "FINANCIER has no PURCHASE_* grant" guarantee is asserted by
     *  ProformaExternalOrderResourcesMigrationIntegrationTest#financierGrants. */
    @Test
    void financierGetsAllProjectIdsLikeBuyer() {
        when(userDao.findRoleCodeById(8L)).thenReturn(Optional.of("FINANCIER"));
        when(projectDao.findAllIds()).thenReturn(List.of(1L, 2L));
        assertEquals(Set.of(1L, 2L), access.allowedProjectIds(8L));
        verify(cache, never()).get(8L);
    }

    @Test
    void isOrderPaymentRoleOnlyForFinancier() {
        assertTrue(access.isOrderPaymentRole(auth("FINANCIER")));
        for (String role : List.of("ADMIN", "MANAGER", "BUYER", "FOREMAN", "CLIENT")) {
            assertFalse(access.isOrderPaymentRole(auth(role)), role);
        }
        assertFalse(access.isOrderPaymentRole(null));
        assertFalse(access.isMoneyPrivileged(auth("FINANCIER")));
    }

    @Test
    void isBuyerOnlyForBuyerRole() {
        assertTrue(access.isBuyer(auth("BUYER")));
        for (String role : List.of("ADMIN", "MANAGER", "FOREMAN", "WORKER", "CLIENT")) {
            assertFalse(access.isBuyer(auth(role)), role);
        }
        assertFalse(access.isBuyer(null));
    }

    @Test
    void isMoneyPrivilegedForAdminManagerBuyerOnly() {
        for (String role : List.of("ADMIN", "MANAGER", "BUYER")) {
            assertTrue(access.isMoneyPrivileged(auth(role)), role);
        }
        for (String role : List.of("FOREMAN", "WORKER", "CLIENT")) {
            assertFalse(access.isMoneyPrivileged(auth(role)), role);
        }
        assertFalse(access.isMoneyPrivileged(null));
    }
}
