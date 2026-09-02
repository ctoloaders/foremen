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
 * Property 3: Permission Replacement Correctness
 * <p>
 * **Validates: Requirements 8.2, 12.3, 13.1**
 * <p>
 * After replacePermissions, the returned response must match the input exactly:
 * same count of entries, same resource IDs, same operation IDs per resource.
 */
@Tag("Feature: FOR-02-03-abac-entities, Property 3: Permission Replacement Correctness")
class PermissionReplacementPropertyTest {

    @Property(tries = 50)
    void replacePermissionsResultMatchesInput(
            @ForAll("positiveIds") Long roleId,
            @ForAll("permissionEntries") List<PermissionInput> inputs) {

        // Arrange: mock all dependencies
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
        role.setCode("TEST_ROLE");
        role.setNameRU("Тестовая роль");
        role.setNamePL("Rola testowa");
        role.setSystem(false);
        when(roleDao.findById(roleId)).thenReturn(Optional.of(role));

        // Setup resources and operations
        Map<Long, ResourceEntity> resourceMap = new HashMap<>();
        Map<Long, OperationEntity> operationMap = new HashMap<>();

        for (PermissionInput input : inputs) {
            ResourceEntity resource = new ResourceEntity();
            resource.setId(input.resourceId());
            resource.setCode("RES_" + input.resourceId());
            resource.setNameRU("Ресурс " + input.resourceId());
            resource.setNamePL("Zasób " + input.resourceId());
            resourceMap.put(input.resourceId(), resource);
            when(resourceDao.findById(input.resourceId())).thenReturn(Optional.of(resource));

            for (Long opId : input.operationIds()) {
                if (!operationMap.containsKey(opId)) {
                    OperationEntity op = new OperationEntity();
                    op.setId(opId);
                    op.setCode("OP_" + opId);
                    op.setNameRU("Операция " + opId);
                    op.setNamePL("Operacja " + opId);
                    operationMap.put(opId, op);
                }
                when(operationDao.findById(opId)).thenReturn(Optional.of(operationMap.get(opId)));
            }
        }

        // Capture saved entities to simulate getPermissions returning them
        List<RoleResourceEntity> savedEntities = new ArrayList<>();
        when(roleResourceDao.saveAll(any())).thenAnswer(invocation -> {
            Iterable<RoleResourceEntity> entities = invocation.getArgument(0);
            entities.forEach(savedEntities::add);
            return savedEntities;
        });

        // On the second findById call (in getPermissions), return the role
        // On findAllByRoleId, return the saved entities
        when(roleResourceDao.findAllByRoleId(roleId)).thenAnswer(inv -> new ArrayList<>(savedEntities));

        // Build the request
        List<PermissionEntryRequest> requestEntries = inputs.stream()
                .map(pi -> new PermissionEntryRequest(pi.resourceId(), pi.operationIds()))
                .toList();
        RolePermissionRequest request = new RolePermissionRequest(requestEntries);

        // Act
        RolePermissionResponse response = roleService.replacePermissions(roleId, request);

        // Assert: response contains same number of entries
        assertEquals(inputs.size(), response.permissions().size(),
                "Response should have same number of permission entries as input");

        // Assert: all resource IDs from input are in response
        Set<Long> inputResourceIds = inputs.stream()
                .map(PermissionInput::resourceId)
                .collect(Collectors.toSet());
        Set<Long> responseResourceIds = response.permissions().stream()
                .map(PermissionEntryResponse::resourceId)
                .collect(Collectors.toSet());
        assertEquals(inputResourceIds, responseResourceIds,
                "Response resource IDs should match input resource IDs");

        // Assert: for each resource, operations match
        for (PermissionInput input : inputs) {
            Optional<PermissionEntryResponse> matchingEntry = response.permissions().stream()
                    .filter(e -> e.resourceId().equals(input.resourceId()))
                    .findFirst();
            assertTrue(matchingEntry.isPresent(),
                    "Response should contain entry for resource " + input.resourceId());

            Set<Long> inputOpIds = new HashSet<>(input.operationIds());
            Set<Long> responseOpIds = matchingEntry.get().operations().stream()
                    .map(OperationInfo::operationId)
                    .collect(Collectors.toSet());
            assertEquals(inputOpIds, responseOpIds,
                    "Operations for resource " + input.resourceId() + " should match");
        }

        // Verify old permissions were deleted
        verify(roleResourceDao).deleteAllByRoleId(roleId);
    }

    // --- Custom data types and providers ---

    record PermissionInput(Long resourceId, List<Long> operationIds) {}

    @Provide
    Arbitrary<Long> positiveIds() {
        return Arbitraries.longs().between(1L, 1000L);
    }

    @Provide
    Arbitrary<List<PermissionInput>> permissionEntries() {
        // Generate 1..5 unique resource entries, each with 1..4 unique operation IDs
        Arbitrary<Long> resourceIds = Arbitraries.longs().between(1L, 50L);
        Arbitrary<List<Long>> operationIdLists = Arbitraries.longs().between(1L, 4L)
                .list().ofMinSize(1).ofMaxSize(4)
                .map(list -> list.stream().distinct().toList());

        return Combinators.combine(resourceIds, operationIdLists)
                .as(PermissionInput::new)
                .list()
                .ofMinSize(1)
                .ofMaxSize(5)
                .map(list -> list.stream()
                        .collect(Collectors.toMap(PermissionInput::resourceId, pi -> pi, (a, b) -> a))
                        .values().stream().toList());
    }
}
