package com.foremen.controller;

import com.foremen.dao.UserDao;
import com.foremen.dao.model.BaseEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.AdminService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.audit.AuditPerformedByResolver;
import com.foremen.service.model.AuditServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import com.foremen.service.model.mapper.AuditServiceMapperImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminControllerDefaultMethodsTest {

    // --- Simple test types (records) ---

    record TestServiceModel(Long id, String name) {}
    record TestServiceExtendedModel(Long id, String name, String description) {}
    record TestDtoModel(Long id, String name) {}
    record TestDtoExtendedModel(Long id, String name, String description) {}
    record TestCreateRequest(String name, String description) {}
    record TestCreateResponse(Long id, String name) {}
    record TestUpdateRequest(String name, String description) {}
    record TestUpdateResponse(Long id, String name, String description) {}

    static class TestDaoEntity extends BaseEntity {
        private String name;
        private String description;
    }

    // --- Mocks ---

    @Mock
    private ControllerToServiceMapper<TestServiceModel, TestServiceExtendedModel, TestDtoModel,
            TestDtoExtendedModel, TestCreateRequest, TestCreateResponse, TestUpdateRequest, TestUpdateResponse> mapper;

    @Mock
    private AdminService<TestServiceModel, TestServiceExtendedModel, TestDaoEntity, Long> service;

    @Mock
    private AuditLogDao auditLogDao;

    @Mock
    private UserDao userDao;

    // Real audit mapper (MapStruct impl) over a real resolver backed by the mocked UserDao, so the
    // getAudit default method's mapping to AuditServiceModel is actually exercised.
    private AuditServiceMapper auditServiceMapper;

    @Mock
    private ServiceToDaoMapper<TestDaoEntity, TestServiceModel, TestServiceExtendedModel> serviceToDaoMapper;

    // --- Controller under test ---

    private AdminController<TestServiceModel, TestServiceExtendedModel, TestDtoModel, TestDtoExtendedModel,
            TestDaoEntity, Long, TestCreateRequest, TestCreateResponse, TestUpdateRequest, TestUpdateResponse> controller;

    @BeforeEach
    void setUp() throws Exception {
        auditServiceMapper = new AuditServiceMapperImpl();
        java.lang.reflect.Field resolverField =
                AuditServiceMapper.class.getDeclaredField("performedByResolver");
        resolverField.setAccessible(true);
        resolverField.set(auditServiceMapper, new AuditPerformedByResolver(userDao));

        controller = new AdminController<>() {
            @Override
            public ControllerToServiceMapper<TestServiceModel, TestServiceExtendedModel, TestDtoModel,
                    TestDtoExtendedModel, TestCreateRequest, TestCreateResponse, TestUpdateRequest, TestUpdateResponse> getMapper() {
                return mapper;
            }

            @Override
            public AdminService<TestServiceModel, TestServiceExtendedModel, TestDaoEntity, Long> getService() {
                return service;
            }

            @Override
            public AuditServiceMapper getAuditServiceMapper() {
                return auditServiceMapper;
            }
        };
    }

    // --- CREATE ---

    @Test
    @DisplayName("create maps request → calls service.create → maps response")
    void createMapsRequestCallsServiceMapsResponse() {
        var request = new TestCreateRequest("Test", "Desc");
        var serviceModel = new TestServiceExtendedModel(null, "Test", "Desc");
        var createdModel = new TestServiceExtendedModel(1L, "Test", "Desc");
        var response = new TestCreateResponse(1L, "Test");

        when(mapper.toServiceExtendedModel(request)).thenReturn(serviceModel);
        when(service.create(serviceModel)).thenReturn(createdModel);
        when(mapper.toCreateResponse(createdModel)).thenReturn(response);

        ResponseEntity<TestCreateResponse> result = controller.create(request);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo(response);
        verify(mapper).toServiceExtendedModel(request);
        verify(service).create(serviceModel);
        verify(mapper).toCreateResponse(createdModel);
    }

    // --- BULK CREATE ---

    @Test
    @DisplayName("createBulk maps each request → calls service.create(list) → maps each response")
    void createBulkMapsEachRequestCallsServiceBulkMapsResponses() {
        var req1 = new TestCreateRequest("A", "DescA");
        var req2 = new TestCreateRequest("B", "DescB");
        var sm1 = new TestServiceExtendedModel(null, "A", "DescA");
        var sm2 = new TestServiceExtendedModel(null, "B", "DescB");
        var created1 = new TestServiceExtendedModel(1L, "A", "DescA");
        var created2 = new TestServiceExtendedModel(2L, "B", "DescB");
        var resp1 = new TestCreateResponse(1L, "A");
        var resp2 = new TestCreateResponse(2L, "B");

        when(mapper.toServiceExtendedModel(req1)).thenReturn(sm1);
        when(mapper.toServiceExtendedModel(req2)).thenReturn(sm2);
        when(service.create(List.of(sm1, sm2))).thenReturn(List.of(created1, created2));
        when(mapper.toCreateResponse(created1)).thenReturn(resp1);
        when(mapper.toCreateResponse(created2)).thenReturn(resp2);

        ResponseEntity<List<TestCreateResponse>> result = controller.createBulk(List.of(req1, req2));

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).containsExactly(resp1, resp2);
        verify(service).create(List.of(sm1, sm2));
    }

    // --- UPDATE ---

    @Test
    @DisplayName("update maps request → calls service.update(id, model) → maps response")
    void updateMapsRequestCallsServiceMapsResponse() {
        Long id = 42L;
        var request = new TestUpdateRequest("Updated", "NewDesc");
        var serviceModel = new TestServiceExtendedModel(null, "Updated", "NewDesc");
        var updatedModel = new TestServiceExtendedModel(42L, "Updated", "NewDesc");
        var response = new TestUpdateResponse(42L, "Updated", "NewDesc");

        when(mapper.toUpdateServiceExtendedModel(request)).thenReturn(serviceModel);
        when(service.update(eq(id), eq(serviceModel))).thenReturn(updatedModel);
        when(mapper.toUpdateResponse(updatedModel)).thenReturn(response);

        ResponseEntity<TestUpdateResponse> result = controller.update(id, request);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo(response);
        verify(mapper).toUpdateServiceExtendedModel(request);
        verify(service).update(id, serviceModel);
        verify(mapper).toUpdateResponse(updatedModel);
    }

    // --- FIND ---

    @Test
    @DisplayName("find with null query → service.find(pageable, null)")
    void findWithNullQueryCallsServiceWithNull() {
        Pageable pageable = PageRequest.of(0, 10);
        var sm = new TestServiceModel(1L, "Test");
        var dto = new TestDtoModel(1L, "Test");
        Page<TestServiceModel> page = new PageImpl<>(List.of(sm), pageable, 1);

        when(service.find(pageable, null)).thenReturn(page);
        when(mapper.toDto(sm)).thenReturn(dto);

        ResponseEntity<Page<TestDtoModel>> result = controller.find(pageable, null);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody().getContent()).containsExactly(dto);
        verify(service).find(pageable, null);
    }

    @Test
    @DisplayName("find with query → addCustomQueryCondition applied → service.find(pageable, processedQuery)")
    void findWithQueryPassesProcessedQueryToService() {
        Pageable pageable = PageRequest.of(0, 20);
        String query = "name==Test";
        Page<TestServiceModel> page = new PageImpl<>(List.of(), pageable, 0);

        when(service.find(pageable, query)).thenReturn(page);

        ResponseEntity<Page<TestDtoModel>> result = controller.find(pageable, query);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        verify(service).find(pageable, query);
    }

    // --- FIND EXTENDED ---

    @Test
    @DisplayName("findExtended calls service.findExtended and maps with toExtendedDto")
    void findExtendedCallsServiceAndMapsWithToExtendedDto() {
        Pageable pageable = PageRequest.of(0, 10);
        var sem = new TestServiceExtendedModel(1L, "Test", "Desc");
        var dtoExt = new TestDtoExtendedModel(1L, "Test", "Desc");
        Page<TestServiceExtendedModel> page = new PageImpl<>(List.of(sem), pageable, 1);

        when(service.findExtended(pageable, null)).thenReturn(page);
        when(mapper.toExtendedDto(sem)).thenReturn(dtoExt);

        ResponseEntity<Page<TestDtoExtendedModel>> result = controller.findExtended(pageable, null);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody().getContent()).containsExactly(dtoExt);
        verify(service).findExtended(pageable, null);
        verify(mapper).toExtendedDto(sem);
    }

    // --- FIND BY ID ---

    @Test
    @DisplayName("findById calls service.findById and maps with toExtendedDto")
    void findByIdCallsServiceAndMapsWithToExtendedDto() {
        Long id = 7L;
        var sem = new TestServiceExtendedModel(7L, "Entity", "Details");
        var dtoExt = new TestDtoExtendedModel(7L, "Entity", "Details");

        when(service.findById(id)).thenReturn(sem);
        when(mapper.toExtendedDto(sem)).thenReturn(dtoExt);

        ResponseEntity<TestDtoExtendedModel> result = controller.findById(id);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo(dtoExt);
        verify(service).findById(id);
        verify(mapper).toExtendedDto(sem);
    }

    // --- GET COUNT ---

    @Test
    @DisplayName("getCount with null query → service.getCount(null)")
    void getCountWithNullQueryCallsServiceWithNull() {
        when(service.getCount(null)).thenReturn(42L);

        ResponseEntity<Long> result = controller.getCount(null);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo(42L);
        verify(service).getCount(null);
    }

    // --- GET AUDIT ---

    @Test
    @DisplayName("getAudit maps rows to AuditServiceModel (performedBy resolved to name, "
            + "snapshots as maps) and calls auditLogDao.findByEntityClassAndEntityId")
    void getAuditMapsRowsToServiceModel() {
        Long entityId = 5L;
        var auditEntry = new AuditLogEntity();
        auditEntry.setEntityClass("TestDaoEntity");
        auditEntry.setEntityId(entityId);
        auditEntry.setOperation("UPDATE");
        // Stored as the acting user's id string, not a name.
        auditEntry.setPerformedBy("11");
        auditEntry.setSnapshotBefore("{\"name\":\"old\"}");
        auditEntry.setSnapshotAfter("{\"name\":\"new\"}");

        var actingUser = new UserEntity();
        actingUser.setName("Иван Петров");

        when(service.getAuditLogDao()).thenReturn(auditLogDao);
        when(service.getDaoModelClass()).thenReturn((Class) TestDaoEntity.class);
        when(auditLogDao.findByEntityClassAndEntityIdOrderByPerformedAtAsc("TestDaoEntity", entityId))
                .thenReturn(List.of(auditEntry));
        when(userDao.findById(11L)).thenReturn(java.util.Optional.of(actingUser));

        ResponseEntity<List<AuditServiceModel>> result = controller.getAudit(entityId);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).hasSize(1);
        AuditServiceModel model = result.getBody().get(0);
        assertThat(model.entityClass()).isEqualTo("TestDaoEntity");
        assertThat(model.entityId()).isEqualTo(entityId);
        assertThat(model.operation()).isEqualTo("UPDATE");
        // performedBy resolved from stored id "11" to the acting user's name.
        assertThat(model.performedBy()).isEqualTo("Иван Петров");
        // snapshots parsed into maps.
        assertThat(model.snapshotBefore()).containsEntry("name", "old");
        assertThat(model.snapshotAfter()).containsEntry("name", "new");
        verify(auditLogDao).findByEntityClassAndEntityIdOrderByPerformedAtAsc("TestDaoEntity", entityId);
    }

    // --- DELETE BY ID ---

    @Test
    @DisplayName("deleteById calls service.deleteById")
    void deleteByIdCallsService() {
        Long id = 99L;

        ResponseEntity<Void> result = controller.deleteById(id);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        verify(service).deleteById(id);
    }

    // --- SET PROPERTIES TO NULL ---

    @Test
    @DisplayName("setPropertiesToNull calls service.setPropertiesToNull with ID and property set")
    void setPropertiesToNullCallsServiceWithIdAndProperties() {
        Long id = 10L;
        Set<String> properties = Set.of("name", "description");

        ResponseEntity<Void> result = controller.setPropertiesToNull(id, properties);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        verify(service).setPropertiesToNull(id, properties);
    }

    // --- GET I18N PROPERTIES ---

    @Test
    @DisplayName("getI18nProperties calls service.getMapper().getI18nSupportedProperties()")
    void getI18nPropertiesCallsServiceMapper() {
        Set<String> i18nProps = Set.of("name", "description");

        when(service.getMapper()).thenReturn(serviceToDaoMapper);
        when(serviceToDaoMapper.getI18nSupportedProperties()).thenReturn(i18nProps);

        ResponseEntity<Collection<String>> result = controller.getI18nProperties();

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).containsExactlyInAnyOrder("name", "description");
        verify(service).getMapper();
        verify(serviceToDaoMapper).getI18nSupportedProperties();
    }

    // --- ADD CUSTOM QUERY CONDITION ---

    @Test
    @DisplayName("addCustomQueryCondition returns query unchanged by default")
    void addCustomQueryConditionReturnsQueryUnchanged() {
        String query = "status==active;name==Test";

        String result = controller.addCustomQueryCondition(query);

        assertThat(result).isEqualTo(query);
    }

    @Test
    @DisplayName("addCustomQueryCondition returns null unchanged")
    void addCustomQueryConditionReturnsNullUnchanged() {
        String result = controller.addCustomQueryCondition(null);

        assertThat(result).isNull();
    }
}
