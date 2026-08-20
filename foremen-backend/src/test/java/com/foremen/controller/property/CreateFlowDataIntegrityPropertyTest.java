package com.foremen.controller.property;

import com.foremen.controller.AdminController;
import com.foremen.dao.model.BaseEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property 1: Create Flow Data Integrity
 *
 * For any valid CreateRequestModel, calling the AdminController's create default method
 * SHALL produce a CreateResponseModel where all non-id fields that exist in both the
 * request and response satisfy value preservation through the mapping chain:
 * toServiceExtendedModel(request) → service.create() → toCreateResponse(result).
 * The response SHALL contain the same field values as the request (for directly-mapped fields),
 * plus a non-null ID assigned by the persistence layer.
 *
 * Validates: Requirements 2.1, 3.1
 */
@Tag("Feature: FOR-01-07-crud-controller, Property 1: Create Flow Data Integrity")
class CreateFlowDataIntegrityPropertyTest {

    // --- Test record types ---

    record TestCreateRequest(String name, String description, Integer priority) {}

    record TestServiceExtendedModel(Long id, String name, String description, Integer priority) {}

    record TestCreateResponse(Long id, String name, String description, Integer priority) {}

    static class TestDaoEntity extends BaseEntity {
        private String name;
    }

    // --- Mocks ---

    @SuppressWarnings("unchecked")
    private final ControllerToServiceMapper<Object, TestServiceExtendedModel, Object, Object,
            TestCreateRequest, TestCreateResponse, Object, Object> mapper = Mockito.mock(ControllerToServiceMapper.class);

    @SuppressWarnings("unchecked")
    private final AdminService<Object, TestServiceExtendedModel, TestDaoEntity, Long> service = Mockito.mock(AdminService.class);

    // --- Controller under test ---

    private final AdminController<Object, TestServiceExtendedModel, Object, Object,
            TestDaoEntity, Long, TestCreateRequest, TestCreateResponse, Object, Object> controller =
            new AdminController<>() {
                @Override
                public ControllerToServiceMapper<Object, TestServiceExtendedModel, Object, Object,
                        TestCreateRequest, TestCreateResponse, Object, Object> getMapper() {
                    return mapper;
                }

                @Override
                public AdminService<Object, TestServiceExtendedModel, TestDaoEntity, Long> getService() {
                    return service;
                }
            };

    @BeforeProperty
    void setUp() {
        Mockito.reset(mapper, service);

        // Mapper: toServiceExtendedModel preserves all request fields, sets id to null
        when(mapper.toServiceExtendedModel(any())).thenAnswer(invocation -> {
            TestCreateRequest req = invocation.getArgument(0);
            return new TestServiceExtendedModel(null, req.name(), req.description(), req.priority());
        });

        // Service: create echoes the model with a generated non-null ID
        when(service.create(any(TestServiceExtendedModel.class))).thenAnswer(invocation -> {
            TestServiceExtendedModel model = invocation.getArgument(0);
            // Simulate persistence layer assigning a non-null ID
            return new TestServiceExtendedModel(
                    System.nanoTime(), // generated ID - always non-null
                    model.name(),
                    model.description(),
                    model.priority()
            );
        });

        // Mapper: toCreateResponse preserves all fields from service model
        when(mapper.toCreateResponse(any())).thenAnswer(invocation -> {
            TestServiceExtendedModel model = invocation.getArgument(0);
            return new TestCreateResponse(model.id(), model.name(), model.description(), model.priority());
        });
    }

    // --- Property: create flow preserves all request fields and assigns non-null ID ---

    @Property(tries = 100)
    void createFlowPreservesAllRequestFieldsAndAssignsNonNullId(
            @ForAll("createRequests") TestCreateRequest request
    ) {
        ResponseEntity<TestCreateResponse> result = controller.create(request);

        assertThat(result.getStatusCode().value()).isEqualTo(200);

        TestCreateResponse response = result.getBody();
        assertThat(response).isNotNull();

        // ID must be non-null (assigned by persistence layer)
        assertThat(response.id()).isNotNull();

        // All request fields must be preserved in the response
        assertThat(response.name()).isEqualTo(request.name());
        assertThat(response.description()).isEqualTo(request.description());
        assertThat(response.priority()).isEqualTo(request.priority());
    }

    // --- Providers ---

    @Provide
    Arbitrary<TestCreateRequest> createRequests() {
        Arbitrary<String> names = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(50);

        Arbitrary<String> descriptions = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(0)
                .ofMaxLength(200);

        Arbitrary<Integer> priorities = Arbitraries.integers().between(0, 1000);

        return Combinators.combine(names, descriptions, priorities)
                .as(TestCreateRequest::new);
    }
}
