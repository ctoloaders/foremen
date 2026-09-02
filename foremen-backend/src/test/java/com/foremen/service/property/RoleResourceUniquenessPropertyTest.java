package com.foremen.service.property;

import com.foremen.controller.model.*;
import com.foremen.dao.OperationDao;
import com.foremen.dao.ResourceDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.RoleResourceDao;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.service.RoleService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.mapper.RoleServiceMapper;
import com.foremen.service.permission.PermissionCache;
import jakarta.persistence.EntityManager;
import net.jqwik.api.*;
import org.mockito.Mockito;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Property 4: Role-Resource Uniqueness Invariant
 * <p>
 * **Validates: Requirements 13.1**
 * <p>
 * After any sequence of replacePermissions calls, there must be no duplicate
 * (role_id, resource_id) pairs in the persisted RoleResourceEntities.
 */
@Tag("Feature: FOR-02-03-abac-entities, Property 4: Role-Resource Uniqueness Invariant")
class RoleResourceUniquenessPropertyTest {

    @Property(tries = 50)
    void noDuplicateRoleResourcePairsAfterReplace(
            @ForAll("positiveIds") Long roleId,
            @ForAll("permissionSequences") List<List<PermissionInput>> callSequence) {

        // Arrange: mocked dependencies
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

        // Setup role
        RoleEntity role = new RoleEntity();
        role.setId(roleId);
        role.setCode("UNIQUENESS_ROLE");
        role.setNameRU("Роль");
        role.setNamePL("Rola");
        role.setSystem(false);
        when(roleDao.findById(roleId)).thenReturn(Optional.of(role));

        // Track the "current state" of saved entities — simulates the database
        List<RoleResourceEntity> currentState = new ArrayList<>();

        // Setup resource and operation mocks for all IDs used in any call
        Set<Long> allResourceIds = callSequence.stream()
                .flatMap(List::stream)
                .map(PermissionInput::resourceId)
                .collect(Collectors.toSet());
        Set<Long> allOperationIds = callSequence.stream()
                .flatMap(List::stream)
                .flatMap(pi -> pi.operationIds().stream())
                .collect(Collectors.toSet());

        for (Long resId : allResourceIds) {
            ResourceEntity resource = new ResourceEntity();
            resource.setId(resId);
            resource.setCode("RES_" + resId);
            resource.setNameRU("Ресурс " + resId);
            resource.setNamePL("Zasób " + resId);
            when(resourceDao.findById(resId)).thenReturn(Optional.of(resource));
        }

        for (Long opId : allOperationIds) {
            OperationEntity op = new OperationEntity();
            op.setId(opId);
            op.setCode("OP_" + opId);
            op.setNameRU("Операция " + opId);
            op.setNamePL("Operacja " + opId);
            when(operationDao.findById(opId)).thenReturn(Optional.of(op));
        }

        // deleteAllByRoleId clears currentState
        doAnswer(inv -> {
            currentState.clear();
            return null;
        }).when(roleResourceDao).deleteAllByRoleId(roleId);

        // saveAll adds to currentState
        when(roleResourceDao.saveAll(any())).thenAnswer(invocation -> {
            Iterable<RoleResourceEntity> entities = invocation.getArgument(0);
            entities.forEach(currentState::add);
            return new ArrayList<>(currentState);
        });

        // findAllByRoleId returns currentState
        when(roleResourceDao.findAllByRoleId(roleId)).thenAnswer(inv -> new ArrayList<>(currentState));

        // Act: execute each call in the sequence
        for (List<PermissionInput> inputs : callSequence) {
            List<PermissionEntryRequest> requestEntries = inputs.stream()
                    .map(pi -> new PermissionEntryRequest(pi.resourceId(), pi.operationIds()))
                    .toList();
            RolePermissionRequest request = new RolePermissionRequest(requestEntries);

            roleService.replacePermissions(roleId, request);

            // Assert: after EACH call, no duplicate (role_id, resource_id) pairs
            List<Long> resourceIdsInState = currentState.stream()
                    .map(rr -> rr.getResource().getId())
                    .toList();
            Set<Long> uniqueResourceIds = new HashSet<>(resourceIdsInState);
            assertEquals(uniqueResourceIds.size(), resourceIdsInState.size(),
                    "No duplicate (role_id, resource_id) pairs should exist after replacePermissions");
        }
    }

    // --- Custom data types and providers ---

    record PermissionInput(Long resourceId, List<Long> operationIds) {}

    @Provide
    Arbitrary<Long> positiveIds() {
        return Arbitraries.longs().between(1L, 1000L);
    }

    @Provide
    Arbitrary<List<List<PermissionInput>>> permissionSequences() {
        // Each call in the sequence is a list of 1..4 unique-resource-id entries
        Arbitrary<List<PermissionInput>> singleCall = permissionInputList();
        return singleCall.list().ofMinSize(1).ofMaxSize(3);
    }

    private Arbitrary<List<PermissionInput>> permissionInputList() {
        Arbitrary<Long> resourceIds = Arbitraries.longs().between(1L, 20L);
        Arbitrary<List<Long>> operationIdLists = Arbitraries.longs().between(1L, 4L)
                .list().ofMinSize(1).ofMaxSize(4)
                .map(list -> list.stream().distinct().toList());

        return Combinators.combine(resourceIds, operationIdLists)
                .as(PermissionInput::new)
                .list()
                .ofMinSize(1)
                .ofMaxSize(4)
                .map(list -> list.stream()
                        .collect(Collectors.toMap(PermissionInput::resourceId, pi -> pi, (a, b) -> a))
                        .values().stream().toList());
    }
}
