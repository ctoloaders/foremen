package com.foremen.controller.property;

import com.foremen.controller.AdminController;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Property 5: Update Flow Preserves Path ID
 *
 * For any ID and for any valid UpdateRequestModel, the AdminController's update default method
 * SHALL invoke getService().update(id, model) with the exact path variable ID — never an ID
 * from the request body. The service enforces that the entity with that ID exists and applies
 * the update to it.
 *
 * Validates: Requirements 4.1
 */
@Tag("Feature: FOR-01-07-crud-controller, Property 5: Update Flow Preserves Path ID")
class UpdatePathIdPropertyTest {

    // --- Simple test models ---

    record TestUpdateRequest(String name, String description, Long bodyId) {}
    record TestUpdateResponse(Long id, String name, String description) {}
    record TestServiceExtendedModel(Long id, String name, String description) {}

    // --- Property: update always passes path ID to service, never body ID ---

    @Property(tries = 100)
    void updateAlwaysPassesPathIdToService(
            @ForAll("positiveIds") Long pathId,
            @ForAll("updateRequests") TestUpdateRequest request
    ) {
        // Arrange
        @SuppressWarnings("unchecked")
        AdminService<Object, TestServiceExtendedModel, Object, Long> mockService =
                Mockito.mock(AdminService.class);

        @SuppressWarnings("unchecked")
        ControllerToServiceMapper<Object, TestServiceExtendedModel, Object, Object,
                Object, Object, TestUpdateRequest, TestUpdateResponse> mockMapper =
                Mockito.mock(ControllerToServiceMapper.class);

        // Mapper converts request to service model (may contain bodyId from request, but irrelevant)
        TestServiceExtendedModel mappedModel = new TestServiceExtendedModel(
                request.bodyId(), request.name(), request.description());
        when(mockMapper.toUpdateServiceExtendedModel(request)).thenReturn(mappedModel);

        // Service returns updated model
        TestServiceExtendedModel updatedModel = new TestServiceExtendedModel(
                pathId, request.name(), request.description());
        when(mockService.update(any(), any())).thenReturn(updatedModel);

        // Mapper converts result to response
        TestUpdateResponse updateResponse = new TestUpdateResponse(
                pathId, request.name(), request.description());
        when(mockMapper.toUpdateResponse(updatedModel)).thenReturn(updateResponse);

        // Create controller under test
        AdminController<Object, TestServiceExtendedModel, Object, Object, Object, Long,
                Object, Object, TestUpdateRequest, TestUpdateResponse> controller =
                new AdminController<>() {
                    @Override
                    public ControllerToServiceMapper<Object, TestServiceExtendedModel, Object, Object,
                            Object, Object, TestUpdateRequest, TestUpdateResponse> getMapper() {
                        return mockMapper;
                    }

                    @Override
                    public AdminService<Object, TestServiceExtendedModel, Object, Long> getService() {
                        return mockService;
                    }

                    @Override
                    public com.foremen.service.model.mapper.AuditServiceMapper getAuditServiceMapper() {
                        return null; // audit endpoint not exercised by this property test
                    }
                };

        // Act
        controller.update(pathId, request);

        // Assert - capture the ID passed to service.update
        ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<TestServiceExtendedModel> modelCaptor =
                ArgumentCaptor.forClass(TestServiceExtendedModel.class);

        verify(mockService).update(idCaptor.capture(), modelCaptor.capture());

        // The captured ID MUST equal the path variable ID
        assertThat(idCaptor.getValue()).isEqualTo(pathId);

        // The ID must NOT come from the request body (when bodyId differs from pathId)
        if (request.bodyId() != null && !request.bodyId().equals(pathId)) {
            assertThat(idCaptor.getValue()).isNotEqualTo(request.bodyId());
        }
    }

    // --- Property: update passes path ID regardless of what mapper produces ---

    @Property(tries = 100)
    void pathIdIsIndependentOfMappedModelContent(
            @ForAll("positiveIds") Long pathId,
            @ForAll("positiveIds") Long differentId,
            @ForAll("names") String name
    ) {
        // Ensure the IDs are different to prove path ID is used, not mapped model's ID
        Assume.that(!pathId.equals(differentId));

        @SuppressWarnings("unchecked")
        AdminService<Object, TestServiceExtendedModel, Object, Long> mockService =
                Mockito.mock(AdminService.class);

        @SuppressWarnings("unchecked")
        ControllerToServiceMapper<Object, TestServiceExtendedModel, Object, Object,
                Object, Object, TestUpdateRequest, TestUpdateResponse> mockMapper =
                Mockito.mock(ControllerToServiceMapper.class);

        // Mapper produces a model with a DIFFERENT id than the path variable
        TestUpdateRequest request = new TestUpdateRequest(name, "desc", differentId);
        TestServiceExtendedModel mappedModel = new TestServiceExtendedModel(differentId, name, "desc");
        when(mockMapper.toUpdateServiceExtendedModel(request)).thenReturn(mappedModel);

        TestServiceExtendedModel updatedModel = new TestServiceExtendedModel(pathId, name, "desc");
        when(mockService.update(any(), any())).thenReturn(updatedModel);

        TestUpdateResponse response = new TestUpdateResponse(pathId, name, "desc");
        when(mockMapper.toUpdateResponse(updatedModel)).thenReturn(response);

        AdminController<Object, TestServiceExtendedModel, Object, Object, Object, Long,
                Object, Object, TestUpdateRequest, TestUpdateResponse> controller =
                new AdminController<>() {
                    @Override
                    public ControllerToServiceMapper<Object, TestServiceExtendedModel, Object, Object,
                            Object, Object, TestUpdateRequest, TestUpdateResponse> getMapper() {
                        return mockMapper;
                    }

                    @Override
                    public AdminService<Object, TestServiceExtendedModel, Object, Long> getService() {
                        return mockService;
                    }

                    @Override
                    public com.foremen.service.model.mapper.AuditServiceMapper getAuditServiceMapper() {
                        return null; // audit endpoint not exercised by this property test
                    }
                };

        // Act
        controller.update(pathId, request);

        // Assert - the path ID is always what gets passed to service
        ArgumentCaptor<Long> idCaptor = ArgumentCaptor.forClass(Long.class);
        verify(mockService).update(idCaptor.capture(), any());

        assertThat(idCaptor.getValue())
                .as("Service must receive the path variable ID (%d), not the body/mapped ID (%d)",
                        pathId, differentId)
                .isEqualTo(pathId);
    }

    // --- Providers ---

    @Provide
    Arbitrary<Long> positiveIds() {
        return Arbitraries.longs().between(1L, Long.MAX_VALUE);
    }

    @Provide
    Arbitrary<TestUpdateRequest> updateRequests() {
        Arbitrary<String> names = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(50);
        Arbitrary<String> descriptions = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(0)
                .ofMaxLength(100);
        Arbitrary<Long> bodyIds = Arbitraries.longs().between(1L, Long.MAX_VALUE)
                .injectNull(0.3); // 30% chance of null body ID

        return Combinators.combine(names, descriptions, bodyIds)
                .as(TestUpdateRequest::new);
    }

    @Provide
    Arbitrary<String> names() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(30);
    }
}
