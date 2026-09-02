package com.foremen.service.property;

import com.foremen.dao.OperationDao;
import com.foremen.dao.ResourceDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.RoleResourceDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.RoleService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.mapper.RoleServiceMapper;
import com.foremen.service.permission.PermissionCache;
import jakarta.persistence.EntityManager;
import net.jqwik.api.*;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Property 2: System Role Deletion Protection
 * <p>
 * **Validates: Requirements 7.5, 13.5**
 * <p>
 * For any role with system=true, deleteById must throw ForemenApiException with HTTP 403.
 */
@Tag("Feature: FOR-02-03-abac-entities, Property 2: System Role Deletion Protection")
class SystemRoleDeletionPropertyTest {

    @Property(tries = 100)
    void deletingSystemRoleThrows403(
            @ForAll("positiveIds") Long roleId,
            @ForAll("nonBlankStrings") String code,
            @ForAll("nonBlankStrings") String nameRU,
            @ForAll("nonBlankStrings") String namePL) {

        // Arrange: create mocked dependencies
        RoleDao roleDao = Mockito.mock(RoleDao.class);
        RoleResourceDao roleResourceDao = Mockito.mock(RoleResourceDao.class);
        ResourceDao resourceDao = Mockito.mock(ResourceDao.class);
        OperationDao operationDao = Mockito.mock(OperationDao.class);
        RoleServiceMapper mapper = Mockito.mock(RoleServiceMapper.class);
        AuditLogDao auditLogDao = Mockito.mock(AuditLogDao.class);
        EntityManager entityManager = Mockito.mock(EntityManager.class);
        PermissionCache permissionCache = Mockito.mock(PermissionCache.class);

        RoleService roleService = new RoleService(
                roleDao, roleResourceDao, resourceDao, operationDao,
                mapper, auditLogDao, entityManager, permissionCache);

        // Create a system role entity
        RoleEntity systemRole = new RoleEntity();
        systemRole.setId(roleId);
        systemRole.setCode(code);
        systemRole.setNameRU(nameRU);
        systemRole.setNamePL(namePL);
        systemRole.setSystem(true);

        when(roleDao.findById(roleId)).thenReturn(Optional.of(systemRole));

        // Act & Assert: deleteById should throw 403 for system roles
        ForemenApiException exception = assertThrows(ForemenApiException.class,
                () -> roleService.deleteById(roleId));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());

        // Verify that deleteById was never called on the DAO
        verify(roleDao, never()).deleteById(any());
    }

    @Property(tries = 100)
    void deletingNonSystemRoleSucceeds(
            @ForAll("positiveIds") Long roleId,
            @ForAll("nonBlankStrings") String code,
            @ForAll("nonBlankStrings") String nameRU,
            @ForAll("nonBlankStrings") String namePL) {

        // Arrange
        RoleDao roleDao = Mockito.mock(RoleDao.class);
        RoleResourceDao roleResourceDao = Mockito.mock(RoleResourceDao.class);
        ResourceDao resourceDao = Mockito.mock(ResourceDao.class);
        OperationDao operationDao = Mockito.mock(OperationDao.class);
        RoleServiceMapper mapper = Mockito.mock(RoleServiceMapper.class);
        AuditLogDao auditLogDao = Mockito.mock(AuditLogDao.class);
        EntityManager entityManager = Mockito.mock(EntityManager.class);
        PermissionCache permissionCache = Mockito.mock(PermissionCache.class);

        RoleService roleService = new RoleService(
                roleDao, roleResourceDao, resourceDao, operationDao,
                mapper, auditLogDao, entityManager, permissionCache);

        // Create a non-system role
        RoleEntity nonSystemRole = new RoleEntity();
        nonSystemRole.setId(roleId);
        nonSystemRole.setCode(code);
        nonSystemRole.setNameRU(nameRU);
        nonSystemRole.setNamePL(namePL);
        nonSystemRole.setSystem(false);

        when(roleDao.findById(roleId)).thenReturn(Optional.of(nonSystemRole));

        // Act: should not throw
        assertDoesNotThrow(() -> roleService.deleteById(roleId));

        // Verify deletion was invoked
        verify(roleDao).deleteById(roleId);
    }

    @Provide
    Arbitrary<Long> positiveIds() {
        return Arbitraries.longs().between(1L, 10_000L);
    }

    @Provide
    Arbitrary<String> nonBlankStrings() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30);
    }
}
