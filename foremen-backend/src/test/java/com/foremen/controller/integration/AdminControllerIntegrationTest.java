package com.foremen.controller.integration;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.controller.AdminController;
import com.foremen.controller.advice.ForemenControllerAdvice;
import com.foremen.exception.ForemenApiException;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.UserEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.AdminService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.audit.AuditPerformedByResolver;
import com.foremen.service.model.mapper.AuditServiceMapper;
import com.foremen.service.model.mapper.AuditServiceMapperImpl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitWebConfig(AdminControllerIntegrationTest.TestWebConfig.class)
class AdminControllerIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private AdminService<TestServiceModel, TestServiceExtendedModel, TestDaoEntity, Long> mockService;

    @Autowired
    private ControllerToServiceMapper<TestServiceModel, TestServiceExtendedModel, TestDtoModel,
            TestDtoExtendedModel, TestCreateRequest, TestCreateResponse, TestUpdateRequest, TestUpdateResponse> mockMapper;

    @Autowired
    private AuditLogDao mockAuditLogDao;

    @Autowired
    private ServiceToDaoMapper<TestDaoEntity, TestServiceModel, TestServiceExtendedModel> mockServiceToDaoMapper;

    @Autowired
    private UserDao mockUserDao;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        Mockito.reset(mockService, mockMapper, mockAuditLogDao, mockServiceToDaoMapper, mockUserDao);
    }

    // --- Test Models ---

    record TestServiceModel(Long id, String name) {}
    record TestServiceExtendedModel(Long id, String name, String description) {}
    record TestDtoModel(Long id, String name) {}
    record TestDtoExtendedModel(Long id, String name, String description) {}
    record TestCreateRequest(@NotBlank String name, String description) {}
    record TestCreateResponse(Long id, String name) {}
    record TestUpdateRequest(String name, String description) {}
    record TestUpdateResponse(Long id, String name, String description) {}

    static class TestDaoEntity {
        private Long id;
        private String name;
    }

    // --- Configuration ---

    @Configuration
    @EnableWebMvc
    @Import({ForemenControllerAdvice.class, TestAdminController.class})
    static class TestWebConfig implements WebMvcConfigurer {

        @Override
        public void addArgumentResolvers(java.util.List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new PageableHandlerMethodArgumentResolver());
        }

        @Bean
        public ResourceBundleMessageSource messageSource() {
            ResourceBundleMessageSource source = new ResourceBundleMessageSource();
            source.setBasename("messages");
            source.setDefaultEncoding("UTF-8");
            source.setUseCodeAsDefaultMessage(true);
            return source;
        }

        @Bean
        public MessageResolver messageResolver(ResourceBundleMessageSource messageSource) {
            return new MessageResolver(messageSource);
        }

        @Bean
        public LocaleResolver localeResolver() {
            AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
            resolver.setDefaultLocale(Locale.of("pl"));
            return resolver;
        }

        @SuppressWarnings("unchecked")
        @Bean
        public AdminService<TestServiceModel, TestServiceExtendedModel, TestDaoEntity, Long> mockAdminService() {
            return Mockito.mock(AdminService.class);
        }

        @SuppressWarnings("unchecked")
        @Bean
        public ControllerToServiceMapper<TestServiceModel, TestServiceExtendedModel, TestDtoModel,
                TestDtoExtendedModel, TestCreateRequest, TestCreateResponse, TestUpdateRequest, TestUpdateResponse> mockControllerMapper() {
            return Mockito.mock(ControllerToServiceMapper.class);
        }

        @Bean
        public AuditLogDao mockAuditLogDao() {
            return Mockito.mock(AuditLogDao.class);
        }

        @SuppressWarnings("unchecked")
        @Bean
        public ServiceToDaoMapper<TestDaoEntity, TestServiceModel, TestServiceExtendedModel> mockServiceToDaoMapper() {
            return Mockito.mock(ServiceToDaoMapper.class);
        }

        @Bean
        public UserDao mockUserDao() {
            return Mockito.mock(UserDao.class);
        }

        /**
         * Real resolver over the mocked {@link UserDao}. Registered as a bean so the MapStruct-
         * generated {@link AuditServiceMapperImpl} gets it field-injected (it is {@code @Autowired}
         * on the abstract mapper), matching production wiring.
         */
        @Bean
        public AuditPerformedByResolver auditPerformedByResolver(UserDao userDao) {
            return new AuditPerformedByResolver(userDao);
        }

        /**
         * Real audit mapper (MapStruct-generated impl), so the per-entity {@code /audit/{id}}
         * endpoint actually resolves {@code performedBy} and parses snapshots into maps.
         */
        @Bean
        public AuditServiceMapper auditServiceMapper() {
            return new AuditServiceMapperImpl();
        }
    }

    @RestController
    @RequestMapping("/api/admin/test-entity")
    static class TestAdminController implements AdminController<
            TestServiceModel,
            TestServiceExtendedModel,
            TestDtoModel,
            TestDtoExtendedModel,
            TestDaoEntity,
            Long,
            TestCreateRequest,
            TestCreateResponse,
            TestUpdateRequest,
            TestUpdateResponse> {

        @Autowired
        private AdminService<TestServiceModel, TestServiceExtendedModel, TestDaoEntity, Long> mockAdminService;

        @Autowired
        private ControllerToServiceMapper<TestServiceModel, TestServiceExtendedModel, TestDtoModel,
                TestDtoExtendedModel, TestCreateRequest, TestCreateResponse, TestUpdateRequest, TestUpdateResponse> mockControllerMapper;

        @Autowired
        private AuditServiceMapper auditServiceMapper;

        @Override
        public ControllerToServiceMapper<TestServiceModel, TestServiceExtendedModel, TestDtoModel,
                TestDtoExtendedModel, TestCreateRequest, TestCreateResponse, TestUpdateRequest, TestUpdateResponse> getMapper() {
            return mockControllerMapper;
        }

        @Override
        public AdminService<TestServiceModel, TestServiceExtendedModel, TestDaoEntity, Long> getService() {
            return mockAdminService;
        }

        @Override
        public AuditServiceMapper getAuditServiceMapper() {
            return auditServiceMapper;
        }
    }

    // --- CREATE Tests ---

    @Test
    @DisplayName("POST with valid JSON → 200 + CreateResponse")
    void postWithValidJsonReturns200WithCreateResponse() throws Exception {
        var serviceModel = new TestServiceExtendedModel(null, "Test Entity", "A description");
        var createdModel = new TestServiceExtendedModel(1L, "Test Entity", "A description");
        var response = new TestCreateResponse(1L, "Test Entity");

        when(mockMapper.toServiceExtendedModel(any())).thenReturn(serviceModel);
        when(mockService.create(serviceModel)).thenReturn(createdModel);
        when(mockMapper.toCreateResponse(createdModel)).thenReturn(response);

        mockMvc.perform(post("/api/admin/test-entity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Test Entity", "description": "A description"}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Test Entity"));
    }

    @Test
    @DisplayName("POST with invalid body (missing required field) → 400 + field errors")
    void postWithInvalidBodyReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/admin/test-entity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description": "No name field"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.fieldErrors").isMap())
                .andExpect(jsonPath("$.fieldErrors.name").exists());
    }

    @Test
    @DisplayName("POST /bulk with list → 200 + list of responses")
    void postBulkReturns200WithListOfResponses() throws Exception {
        var sm1 = new TestServiceExtendedModel(null, "A", "DescA");
        var sm2 = new TestServiceExtendedModel(null, "B", "DescB");
        var created1 = new TestServiceExtendedModel(1L, "A", "DescA");
        var created2 = new TestServiceExtendedModel(2L, "B", "DescB");
        var resp1 = new TestCreateResponse(1L, "A");
        var resp2 = new TestCreateResponse(2L, "B");

        when(mockMapper.toServiceExtendedModel(any())).thenReturn(sm1, sm2);
        when(mockService.create(any(List.class))).thenReturn(List.of(created1, created2));
        when(mockMapper.toCreateResponse(created1)).thenReturn(resp1);
        when(mockMapper.toCreateResponse(created2)).thenReturn(resp2);

        mockMvc.perform(post("/api/admin/test-entity/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"name": "A", "description": "DescA"}, {"name": "B", "description": "DescB"}]
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("A"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].name").value("B"));
    }

    // --- UPDATE Tests ---

    @Test
    @DisplayName("PUT /{id} → 200 + UpdateResponse")
    void putByIdReturns200WithUpdateResponse() throws Exception {
        var serviceModel = new TestServiceExtendedModel(null, "Updated", "NewDesc");
        var updatedModel = new TestServiceExtendedModel(1L, "Updated", "NewDesc");
        var response = new TestUpdateResponse(1L, "Updated", "NewDesc");

        when(mockMapper.toUpdateServiceExtendedModel(any())).thenReturn(serviceModel);
        when(mockService.update(eq(1L), eq(serviceModel))).thenReturn(updatedModel);
        when(mockMapper.toUpdateResponse(updatedModel)).thenReturn(response);

        mockMvc.perform(put("/api/admin/test-entity/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Updated", "description": "NewDesc"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Updated"))
                .andExpect(jsonPath("$.description").value("NewDesc"));
    }

    @Test
    @DisplayName("PUT /{nonExistentId} → 404 + ErrorResponse")
    void putNonExistentIdReturns404() throws Exception {
        when(mockMapper.toUpdateServiceExtendedModel(any())).thenReturn(
                new TestServiceExtendedModel(null, "Updated", "NewDesc"));
        when(mockService.update(eq(999L), any()))
                .thenThrow(new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", 999L));

        mockMvc.perform(put("/api/admin/test-entity/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Updated", "description": "NewDesc"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    // --- FIND (paginated) Tests ---

    @Test
    @DisplayName("GET /?page=0&size=10 → 200 + Page")
    void getWithPaginationReturns200WithPage() throws Exception {
        var sm = new TestServiceModel(1L, "Test");
        var dto = new TestDtoModel(1L, "Test");
        Page<TestServiceModel> page = new PageImpl<>(List.of(sm), PageRequest.of(0, 10), 1);

        when(mockService.find(any(Pageable.class), eq(null))).thenReturn(page);
        when(mockMapper.toDto(sm)).thenReturn(dto);

        mockMvc.perform(get("/api/admin/test-entity")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Test"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.number").value(0));
    }

    @Test
    @DisplayName("GET /?query=name==Test → 200 + filtered Page")
    void getWithQueryReturns200WithFilteredPage() throws Exception {
        var sm = new TestServiceModel(1L, "Test");
        var dto = new TestDtoModel(1L, "Test");
        Page<TestServiceModel> page = new PageImpl<>(List.of(sm), PageRequest.of(0, 20), 1);

        when(mockService.find(any(Pageable.class), eq("name==Test"))).thenReturn(page);
        when(mockMapper.toDto(sm)).thenReturn(dto);

        mockMvc.perform(get("/api/admin/test-entity")
                        .param("page", "0")
                        .param("size", "20")
                        .param("query", "name==Test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Test"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // --- FIND EXTENDED Tests ---

    @Test
    @DisplayName("GET /extended?page=0&size=10 → 200 + Page with extended models")
    void getExtendedReturns200WithExtendedPage() throws Exception {
        var sem = new TestServiceExtendedModel(1L, "Test", "Description");
        var dtoExt = new TestDtoExtendedModel(1L, "Test", "Description");
        Page<TestServiceExtendedModel> page = new PageImpl<>(List.of(sem), PageRequest.of(0, 10), 1);

        when(mockService.findExtended(any(Pageable.class), eq(null))).thenReturn(page);
        when(mockMapper.toExtendedDto(sem)).thenReturn(dtoExt);

        mockMvc.perform(get("/api/admin/test-entity/extended")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Test"))
                .andExpect(jsonPath("$.content[0].description").value("Description"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // --- FIND BY ID Tests ---

    @Test
    @DisplayName("GET /{id} → 200 + DtoExtendedModel")
    void getByIdReturns200WithDtoExtendedModel() throws Exception {
        var sem = new TestServiceExtendedModel(1L, "Entity", "Details");
        var dtoExt = new TestDtoExtendedModel(1L, "Entity", "Details");

        when(mockService.findById(1L)).thenReturn(sem);
        when(mockMapper.toExtendedDto(sem)).thenReturn(dtoExt);

        mockMvc.perform(get("/api/admin/test-entity/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Entity"))
                .andExpect(jsonPath("$.description").value("Details"));
    }

    @Test
    @DisplayName("GET /{nonExistentId} → 404")
    void getByNonExistentIdReturns404() throws Exception {
        when(mockService.findById(999L))
                .thenThrow(new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", 999L));

        mockMvc.perform(get("/api/admin/test-entity/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    // --- COUNT Tests ---

    @Test
    @DisplayName("GET /count → 200 + number")
    void getCountReturns200WithNumber() throws Exception {
        when(mockService.getCount(null)).thenReturn(42L);

        mockMvc.perform(get("/api/admin/test-entity/count"))
                .andExpect(status().isOk())
                .andExpect(content().string("42"));
    }

    @Test
    @DisplayName("GET /count?query=status==active → 200 + filtered count")
    void getCountWithQueryReturns200WithFilteredCount() throws Exception {
        when(mockService.getCount("status==active")).thenReturn(15L);

        mockMvc.perform(get("/api/admin/test-entity/count")
                        .param("query", "status==active"))
                .andExpect(status().isOk())
                .andExpect(content().string("15"));
    }

    // --- AUDIT Tests ---

    @Test
    @DisplayName("GET /audit/{id} → 200 + list of AuditServiceModel (performedBy resolved to name, "
            + "snapshots as objects, no BaseEntity leak)")
    void getAuditReturns200WithMappedAuditRecords() throws Exception {
        var auditEntry = new AuditLogEntity();
        auditEntry.setEntityClass("TestDaoEntity");
        auditEntry.setEntityId(1L);
        auditEntry.setOperation("UPDATE");
        // Stored as the acting user's id string (JWT sub), not a name.
        auditEntry.setPerformedBy("11");
        auditEntry.setPerformedAt(LocalDateTime.of(2024, 1, 15, 10, 30, 0));
        auditEntry.setSnapshotBefore("{\"name\":\"old\"}");
        auditEntry.setSnapshotAfter("{\"name\":\"new\"}");
        // BaseEntity fields that MUST NOT leak into the response.
        auditEntry.setCreatedBy("someone");
        auditEntry.setCreatedDate(LocalDateTime.of(2024, 1, 1, 0, 0, 0));

        var actingUser = new UserEntity();
        actingUser.setName("Иван Петров");

        when(mockService.getAuditLogDao()).thenReturn(mockAuditLogDao);
        when(mockService.getDaoModelClass()).thenReturn((Class) TestDaoEntity.class);
        when(mockAuditLogDao.findByEntityClassAndEntityIdOrderByPerformedAtAsc("TestDaoEntity", 1L))
                .thenReturn(List.of(auditEntry));
        when(mockUserDao.findById(11L)).thenReturn(java.util.Optional.of(actingUser));

        mockMvc.perform(get("/api/admin/test-entity/audit/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entityClass").value("TestDaoEntity"))
                .andExpect(jsonPath("$[0].entityId").value(1))
                .andExpect(jsonPath("$[0].operation").value("UPDATE"))
                // performedBy resolved from the stored id "11" to the acting user's name.
                .andExpect(jsonPath("$[0].performedBy").value("Иван Петров"))
                // snapshots parsed into maps, not raw strings.
                .andExpect(jsonPath("$[0].snapshotBefore.name").value("old"))
                .andExpect(jsonPath("$[0].snapshotAfter.name").value("new"))
                // BaseEntity fields must not leak.
                .andExpect(jsonPath("$[0].createdBy").doesNotExist())
                .andExpect(jsonPath("$[0].createdDate").doesNotExist())
                .andExpect(jsonPath("$[0].updatedBy").doesNotExist())
                .andExpect(jsonPath("$[0].updatedDate").doesNotExist());
    }

    // --- DELETE Tests ---

    @Test
    @DisplayName("DELETE /{id} → 204")
    void deleteByIdReturns204() throws Exception {
        doNothing().when(mockService).deleteById(1L);

        mockMvc.perform(delete("/api/admin/test-entity/1"))
                .andExpect(status().isNoContent());

        verify(mockService).deleteById(1L);
    }

    @Test
    @DisplayName("DELETE /{nonExistentId} → 404")
    void deleteNonExistentIdReturns404() throws Exception {
        doThrow(new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", 999L))
                .when(mockService).deleteById(999L);

        mockMvc.perform(delete("/api/admin/test-entity/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    // --- SET PROPERTIES TO NULL Tests ---

    @Test
    @DisplayName("DELETE /{id}/property?properties=field1,field2 → 204")
    void deletePropertyReturns204() throws Exception {
        doNothing().when(mockService).setPropertiesToNull(eq(1L), eq(Set.of("field1", "field2")));

        mockMvc.perform(delete("/api/admin/test-entity/1/property")
                        .param("properties", "field1", "field2"))
                .andExpect(status().isNoContent());

        verify(mockService).setPropertiesToNull(eq(1L), eq(Set.of("field1", "field2")));
    }

    // --- I18N Tests ---

    @Test
    @DisplayName("GET /i18n → 200 + collection of property names")
    void getI18nReturns200WithPropertyNames() throws Exception {
        when(mockService.getMapper()).thenReturn(mockServiceToDaoMapper);
        when(mockServiceToDaoMapper.getI18nSupportedProperties()).thenReturn(Set.of("name", "description"));

        mockMvc.perform(get("/api/admin/test-entity/i18n"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2));
    }
}
